package ai.slock.android.core.data.realtime

import ai.slock.compose.data.RealtimeSubscription
import ai.slock.compose.data.SLOCK_SOCKET_BASE_URL
import ai.slock.compose.data.realtime.RealtimeAuth
import ai.slock.compose.data.realtime.RealtimeConnectRequest
import ai.slock.compose.data.realtime.RealtimeConnectionState
import ai.slock.compose.data.realtime.RealtimeClientDiagnostics
import ai.slock.compose.data.realtime.RealtimeCursorPolicy
import ai.slock.compose.data.realtime.RealtimeCursorState
import ai.slock.compose.data.realtime.RealtimeCursorUpdate
import ai.slock.compose.data.realtime.RealtimeHeartbeatWatchdog
import ai.slock.compose.data.realtime.RealtimeHeartbeatWatchdogSnapshot
import ai.slock.compose.data.realtime.RealtimeResumeRequest
import ai.slock.compose.data.realtime.RealtimeSocketTransport
import ai.slock.compose.data.realtime.RealtimeSocketEvents
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
import ai.slock.android.core.model.RealtimeRefreshRequired
import ai.slock.android.core.model.ServerMembershipRemoved
import ai.slock.android.core.model.ServerDeleted
import ai.slock.android.core.model.ServerPlanUpdated
import ai.slock.android.core.model.AgentCreated
import ai.slock.android.core.model.SyncResumeResponse
import ai.slock.android.core.model.Heartbeat
import ai.slock.android.core.model.ServerId
import com.google.gson.JsonObject
import java.util.Timer
import java.util.TimerTask
import org.json.JSONObject

class SocketIoRealtimeClient(
    private val transport: RealtimeSocketTransport,
    private val eventMapper: RealtimeEventMapper = RealtimeEventMapper(),
    private val logger: (String) -> Unit = {},
    private val diagnostics: RealtimeClientDiagnostics? = null,
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val listeners = mutableSetOf<(RealtimeEvent) -> Unit>()
    private val rawListeners = mutableSetOf<(String, Any?) -> Unit>()
    private val transportSubscriptions = mutableListOf<RealtimeSubscription>()
    private val joinedChannels = linkedSetOf<ChannelId>()
    private var cursor: RealtimeCursorState = RealtimeCursorPolicy.fromAppliedSeq(0)
    private var connectedServerId: String? = null
    private var currentAuth: RealtimeAuth? = null
    private var authRefreshProvider: ((String) -> RealtimeAuth?)? = null
    private var lastHeartbeatAtMillis: Long? = null
    private var lastInboundEventAtMillis: Long? = null
    private var watchdogTimer: Timer? = null
    var state: RealtimeConnectionState = RealtimeConnectionState.Disconnected
        private set

    fun setAuthRefreshProvider(provider: ((String) -> RealtimeAuth?)?) {
        authRefreshProvider = provider
    }

    fun connect(request: RealtimeConnectRequest) {
        if (connectedServerId != request.auth.serverId.value) {
            joinedChannels.clear()
            connectedServerId = request.auth.serverId.value
        }
        currentAuth = request.auth
        cursor = RealtimeCursorPolicy.fromAppliedSeq(request.lastSeq)
        state = RealtimeConnectionState.Connecting
        lastHeartbeatAtMillis = null
        lastInboundEventAtMillis = null
        diagnostics?.onConnectRequested(request.auth.serverId, request.lastSeq)
        ensureTransportListeners()
        startHeartbeatWatchdog()
        logger("client_connect_start serverId=${request.auth.serverId.value} lastSeq=${request.lastSeq}")
        transport.connect(request.auth.toWireMap())
    }

    fun disconnect() {
        logger("client_disconnect serverId=${connectedServerId.orEmpty()} joinedRooms=${joinedChannels.size}")
        stopHeartbeatWatchdog()
        transport.disconnect()
        state = RealtimeConnectionState.Disconnected
        diagnostics?.onIntentionalDisconnect(
                reason = "client_disconnect",
                serverId = connectedServerId?.let(::ServerId)
        )
    }

    fun checkHeartbeatSilence(nowMillis: Long = clockMillis()): Boolean {
        val shouldReconnect =
            RealtimeHeartbeatWatchdog.shouldForceReconnect(
                RealtimeHeartbeatWatchdogSnapshot(
                    connected = state == RealtimeConnectionState.Connected ||
                        state == RealtimeConnectionState.RoomsJoined,
                    lastHeartbeatAtMillis = lastHeartbeatAtMillis,
                    lastInboundEventAtMillis = lastInboundEventAtMillis
                ),
                nowMillis
            )
        if (!shouldReconnect) return false
        val auth = currentAuth ?: return false
        logger(
            "heartbeat_silence_reconnect serverId=${connectedServerId.orEmpty()} " +
                "lastHeartbeatAt=${lastHeartbeatAtMillis ?: 0L} lastInboundAt=${lastInboundEventAtMillis ?: 0L}"
        )
        diagnostics?.onDisconnected(
            reason = "app_heartbeat_silent",
            serverId = connectedServerId?.let(::ServerId)
        )
        state = RealtimeConnectionState.Connecting
        lastHeartbeatAtMillis = null
        lastInboundEventAtMillis = nowMillis
        transport.connect(auth.toWireMap())
        return true
    }

    fun joinChannel(channelId: ChannelId) {
        val added = joinedChannels.add(channelId)
        logger("join_room channelId=${channelId.value} replay=false added=$added")
        if (!added) return
        emitJoinChannel(channelId)
    }

    fun leaveChannel(channelId: ChannelId) {
        val removed = joinedChannels.remove(channelId)
        logger("leave_room channelId=${channelId.value} removed=$removed")
        if (!removed) return
        emitLeaveChannel(channelId)
    }

    fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription {
        listeners += listener
        return RealtimeSubscription { listeners -= listener }
    }

    fun observeRawEvents(listener: (eventName: String, rawPayload: Any?) -> Unit): RealtimeSubscription {
        rawListeners += listener
        return RealtimeSubscription { rawListeners -= listener }
    }

    private fun ensureTransportListeners() {
        if (transportSubscriptions.isNotEmpty()) return
        transportSubscriptions += transport.on(RealtimeSocketEvents.Connect) {
            markInbound()
            startHeartbeatWatchdog()
            state = RealtimeConnectionState.Connected
            diagnostics?.onConnected(connectedServerId?.let(::ServerId))
            logger(
                "client_connected serverId=${connectedServerId.orEmpty()} joinedRooms=${joinedChannels.size} " +
                    "appliedSeq=${cursor.appliedLastSeq} serverMaxSeq=${cursor.serverMaxSeq}"
            )
            rejoinChannels(reason = "connect")
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.RoomsJoined) {
            markInbound()
            state = RealtimeConnectionState.RoomsJoined
            diagnostics?.onRoomsJoined(
                connectedServerId?.let(::ServerId),
                cursor.resumeLastSeq.takeIf { it > 0L }
            )
            logger(
                "rooms_joined serverId=${connectedServerId.orEmpty()} joinedRooms=${joinedChannels.size} " +
                    "appliedSeq=${cursor.appliedLastSeq} serverMaxSeq=${cursor.serverMaxSeq}"
            )
            emitSyncResume(reason = "rooms_joined")
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.ConnectError) { payload ->
            markInbound()
            state = RealtimeConnectionState.Failed(payload?.toString().orEmpty())
            diagnostics?.onConnectionFailed(
                    reason = payload?.toString().orEmpty(),
                    serverId = connectedServerId?.let(::ServerId)
            )
            logger("client_connect_error serverId=${connectedServerId.orEmpty()} reason=${payload?.javaClass?.simpleName.orEmpty()}")
            if (payload.isAuthFailurePayload()) {
                refreshTransportAuth(reason = "connect_error")
            }
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.Disconnect) {
            stopHeartbeatWatchdog()
            state = RealtimeConnectionState.Disconnected
            diagnostics?.onDisconnected(
                    reason = "transport_disconnect",
                    serverId = connectedServerId?.let(::ServerId)
            )
            logger("client_disconnected serverId=${connectedServerId.orEmpty()}")
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.Reconnect) {
            markInbound()
            startHeartbeatWatchdog()
            state = RealtimeConnectionState.Connected
            diagnostics?.onConnected(connectedServerId?.let(::ServerId))
            logger(
                "client_reconnected serverId=${connectedServerId.orEmpty()} joinedRooms=${joinedChannels.size} " +
                    "appliedSeq=${cursor.appliedLastSeq} serverMaxSeq=${cursor.serverMaxSeq}"
            )
            rejoinChannels(reason = "reconnect")
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.ReconnectAttempt) { payload ->
            refreshTransportAuth(reason = "reconnect_attempt")
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
            stopHeartbeatWatchdog()
            state = RealtimeConnectionState.Failed(payload?.toString().orEmpty())
            diagnostics?.onConnectionFailed(
                    reason = payload?.toString().orEmpty(),
                    serverId = connectedServerId?.let(::ServerId)
            )
            logger("client_reconnect_failed serverId=${connectedServerId.orEmpty()} reason=${payload?.javaClass?.simpleName.orEmpty()}")
        }
        RealtimeWireEventNames.forEach { eventName ->
            transportSubscriptions += transport.on(eventName) { payload ->
                markInbound(eventName)
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
                val cursorUpdate = event?.let(::dispatch)
                if (shouldFanOutRawEvent(eventName, cursorUpdate?.serverAhead == true)) {
                    rawListeners.toList().forEach { it(eventName, payload) }
                }
            }
        }
    }

    private fun shouldFanOutRawEvent(eventName: String, serverAhead: Boolean): Boolean =
        eventName != RealtimeSocketEvents.Heartbeat || serverAhead

    private fun emitJoinChannel(channelId: ChannelId) {
        transport.emit(RealtimeSocketEvents.JoinChannel, mapOf("channelId" to channelId.value))
    }

    private fun emitLeaveChannel(channelId: ChannelId) {
        transport.emit(RealtimeSocketEvents.LeaveChannel, mapOf("channelId" to channelId.value))
    }

    private fun rejoinChannels(reason: String) {
        joinedChannels.forEach { channelId ->
            logger("join_room channelId=${channelId.value} replay=true reason=$reason")
            emitJoinChannel(channelId)
        }
    }

    private fun emitSyncResume(reason: String) {
        val resumeLastSeq = cursor.resumeLastSeq
        if (resumeLastSeq <= 0) return
        logger("sync_resume_emit serverId=${connectedServerId.orEmpty()} lastSeq=$resumeLastSeq reason=$reason")
        transport.emit(RealtimeSocketEvents.SyncResume, RealtimeResumeRequest(resumeLastSeq).toWireMap())
    }

    private fun startHeartbeatWatchdog() {
        if (watchdogTimer != null) return
        watchdogTimer = Timer("slock-realtime-heartbeat-watchdog", true).also { timer ->
            timer.scheduleAtFixedRate(
                object : TimerTask() {
                    override fun run() {
                        checkHeartbeatSilence(clockMillis())
                    }
                },
                RealtimeHeartbeatWatchdog.CHECK_INTERVAL_MILLIS,
                RealtimeHeartbeatWatchdog.CHECK_INTERVAL_MILLIS
            )
        }
    }

    private fun stopHeartbeatWatchdog() {
        watchdogTimer?.cancel()
        watchdogTimer = null
        lastHeartbeatAtMillis = null
        lastInboundEventAtMillis = null
    }

    private fun markInbound(eventName: String? = null) {
        val now = clockMillis()
        lastInboundEventAtMillis = now
        if (eventName == RealtimeSocketEvents.Heartbeat) {
            lastHeartbeatAtMillis = now
        }
    }

    private fun refreshTransportAuth(reason: String) {
        val provider = authRefreshProvider ?: return
        val previous = currentAuth
        val next = provider(reason) ?: run {
            logger("auth_refresh_skip reason=$reason result=missing_session")
            return
        }
        if (previous?.serverId != null && previous.serverId != next.serverId) {
            joinedChannels.clear()
        }
        if (previous == next) {
            logger("auth_refresh_skip reason=$reason result=unchanged serverId=${next.serverId.value}")
            return
        }
        currentAuth = next
        connectedServerId = next.serverId.value
        logger("auth_refresh_apply reason=$reason serverId=${next.serverId.value} tokenChanged=${previous?.token != next.token}")
        state = RealtimeConnectionState.Connecting
        transport.connect(next.toWireMap())
    }

    private fun dispatch(event: RealtimeEvent): RealtimeCursorUpdate {
        val cursorUpdate = RealtimeCursorPolicy.updateFromEvent(cursor, event)
        cursor = cursorUpdate.state
        if (event is Heartbeat && cursorUpdate.serverAhead) {
            logger(
                "heartbeat_gap_detected serverId=${connectedServerId.orEmpty()} " +
                    "serverSeq=${event.seq} appliedSeq=${cursor.appliedLastSeq}"
            )
        }
        diagnostics?.onEvent(event, connectedServerId?.let(::ServerId), visibleThreadId = null)
        logger(
            "event_dispatch type=${event.realtimeDiagnosticName()} appliedSeq=${cursor.appliedLastSeq} " +
                "serverMaxSeq=${cursor.serverMaxSeq} listeners=${listeners.size}"
        )
        listeners.toList().forEach { it(event) }
        return cursorUpdate
    }

    companion object {
        const val DEFAULT_SOCKET_URL = SLOCK_SOCKET_BASE_URL

        val RealtimeWireEventNames = RealtimeSocketEvents.ServerWireEvents
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

private fun RealtimeAuth.toWireMap(): Map<String, String> =
    mapOf(
        "token" to token,
        "serverId" to serverId.value
    )

private fun Any?.isAuthFailurePayload(): Boolean =
    toString().lowercase().let { text ->
        "auth" in text ||
            "token" in text ||
            "unauthorized" in text ||
            "invalid" in text ||
            "expired" in text
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
    is RealtimeRefreshRequired -> "refresh_required"
    is ServerMembershipRemoved -> "server_membership_removed"
    is ServerDeleted -> "server_deleted"
    is ServerPlanUpdated -> "server_plan_updated"
    is AgentCreated -> "agent_created"
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
        "threadId=${threadChannelId.value} messageId=${parentMessageId.value} replyCount=$replyCount"
    is TaskCreated -> "tasks=${tasks.size}"
    is TaskUpdated -> "taskId=${task.id.value}"
    is TaskDeleted -> "taskId=${taskId.value}"
    is MemberPresenceUpdated ->
        "memberId=${memberId.value} presence=$presence kind=${kind?.name.orEmpty()}"
    is RealtimeRefreshRequired ->
        "scope=$scope reason=$reason serverId=${serverId?.value.orEmpty()} channelId=${channelId?.value.orEmpty()} memberId=${memberId?.value.orEmpty()} subjectId=${subjectId.orEmpty()}"
    is ServerMembershipRemoved -> "serverId=${serverId.value}"
    is ServerDeleted -> "serverId=${serverId.value}"
    is ServerPlanUpdated -> "serverId=${serverId.value} plan=${plan}"
    is AgentCreated -> "agentId=${agent.id.value}"
    is SyncResumeResponse ->
        "currentSeq=$currentSeq messages=${messages.size} hasMore=$hasMore"
    is Heartbeat -> "seq=$seq"
}
