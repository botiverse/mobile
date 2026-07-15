package build.raft.app.core.data.realtime

import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.SLOCK_SOCKET_BASE_URL
import build.raft.app.data.realtime.RealtimeAuth
import build.raft.app.data.realtime.RealtimeConnectRequest
import build.raft.app.data.realtime.RealtimeSocketConnectionContext
import build.raft.app.data.realtime.RealtimeConnectionState
import build.raft.app.data.realtime.RealtimeClientDiagnostics
import build.raft.app.data.realtime.RealtimeCursorPolicy
import build.raft.app.data.realtime.RealtimeCursorState
import build.raft.app.data.realtime.RealtimeCursorUpdate
import build.raft.app.data.realtime.RealtimeHeartbeatWatchdog
import build.raft.app.data.realtime.RealtimeHeartbeatWatchdogSnapshot
import build.raft.app.data.realtime.RealtimeResumeRequest
import build.raft.app.data.realtime.RealtimeSocketTransport
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ChannelId
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.MessageNew
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.MessageUpdated
import build.raft.app.model.PerReceiverNotificationPrefsChanged
import build.raft.app.model.PerReceiverPinnedChanged
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.ThreadUpdated
import build.raft.app.model.TaskCreated
import build.raft.app.model.TaskUpdated
import build.raft.app.model.TaskDeleted
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerMembershipRemoved
import build.raft.app.model.ServerDeleted
import build.raft.app.model.ServerPlanUpdated
import build.raft.app.model.AgentCreated
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.Heartbeat
import build.raft.app.model.ServerId
import com.google.gson.JsonObject
import java.util.Timer
import java.util.TimerTask
import org.json.JSONObject
import kotlin.time.TimeSource

class SocketIoRealtimeClient(
    private val transport: RealtimeSocketTransport,
    private val eventMapper: RealtimeEventMapper = RealtimeEventMapper(),
    private val logger: (String) -> Unit = {},
    private val diagnostics: RealtimeClientDiagnostics? = null,
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val listeners = mutableSetOf<(RealtimeEvent) -> Unit>()
    private val rawListeners = mutableSetOf<(RealtimeSocketConnectionContext, String, Any?) -> Unit>()
    private val transportSubscriptions = mutableListOf<RealtimeSubscription>()
    private val joinedChannels = linkedSetOf<ChannelId>()
    private var cursor: RealtimeCursorState = RealtimeCursorPolicy.fromAppliedSeq(0)
    private var connectedServerId: String? = null
    private var currentAuth: RealtimeAuth? = null
    private var currentConnectionContext: RealtimeSocketConnectionContext? = null
    private var authRefreshProvider: ((String) -> RealtimeAuth?)? = null
    private var lastHeartbeatAtMillis: Long? = null
    private var lastInboundEventAtMillis: Long? = null
    private var resumeProjectionRetryLastSeq: Long? = null
    private var watchdogTimer: Timer? = null
    private val successDiagnostics = RealtimeSuccessDiagnostics(clockMillis)
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
        val connectionContext = RealtimeSocketConnectionContext(request.auth.serverId, request.witness)
        currentConnectionContext = connectionContext
        cursor = RealtimeCursorPolicy.fromAppliedSeq(request.lastSeq)
        resumeProjectionRetryLastSeq = null
        state = RealtimeConnectionState.Connecting
        lastHeartbeatAtMillis = null
        lastInboundEventAtMillis = null
        diagnostics?.onConnectRequested(request.auth.serverId, request.lastSeq)
        ensureTransportListeners()
        startHeartbeatWatchdog()
        logger("client_connect_start serverId=${request.auth.serverId.value} lastSeq=${request.lastSeq}")
        transport.connect(request.auth.toWireMap(), connectionContext)
    }

    fun disconnect() {
        logger("client_disconnect serverId=${connectedServerId.orEmpty()} joinedRooms=${joinedChannels.size}")
        stopHeartbeatWatchdog()
        currentConnectionContext = null
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
        val connectionContext = currentConnectionContext ?: return false
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
        rebaselineCursor(reason = "heartbeat_silent")
        transport.connect(auth.toWireMap(), connectionContext)
        return true
    }

    fun joinChannel(channelId: ChannelId) {
        val added = joinedChannels.add(channelId)
        logger("join_room channelId=${channelId.value} replay=false added=$added")
        if (!added) return
        emitRoomPayload(RealtimeSocketEvents.JoinChannel, channelId.value)
    }

    fun leaveChannel(channelId: ChannelId) {
        val removed = joinedChannels.remove(channelId)
        logger("leave_room channelId=${channelId.value} removed=$removed")
        if (!removed) return
        emitRoomPayload(RealtimeSocketEvents.LeaveChannel, channelId.value)
    }

    fun emitRoomCommand(eventName: String, payload: String, force: Boolean = false) {
        when (eventName) {
            RealtimeSocketEvents.JoinChannel -> {
                val channelId = ChannelId(payload)
                val added = joinedChannels.add(channelId)
                logger("join_room channelId=${channelId.value} replay=false added=$added force=$force")
                if (!added && !force) return
                emitRoomPayload(eventName, channelId.value)
            }
            RealtimeSocketEvents.LeaveChannel -> {
                val channelId = ChannelId(payload)
                val removed = joinedChannels.remove(channelId)
                logger("leave_room channelId=${channelId.value} removed=$removed")
                if (!removed) return
                emitRoomPayload(eventName, channelId.value)
            }
            else -> emitRoomPayload(eventName, payload)
        }
    }

    fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription {
        listeners += listener
        return RealtimeSubscription { listeners -= listener }
    }

    fun observeRawEvents(
        listener: (context: RealtimeSocketConnectionContext, eventName: String, rawPayload: Any?) -> Unit
    ): RealtimeSubscription {
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
            rebaselineCursor(reason = "transport_reconnect")
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
                val mapStartedAt = TimeSource.Monotonic.markNow()
                val event = eventMapper.map(eventName, payload)
                val mapDurationMs = mapStartedAt.elapsedNow().inWholeMilliseconds
                if (event == null) {
                    logger(
                        "event_map_ignored eventName=$eventName " +
                            "payloadType=${payload?.javaClass?.simpleName.orEmpty()} durationMs=$mapDurationMs"
                    )
                    if (shouldFanOutRawEvent(eventName, serverAhead = false)) {
                        val connectionContext = currentConnectionContext ?: return@on
                        rawListeners.toList().forEach { it(connectionContext, eventName, payload) }
                    }
                    return@on
                }
                val cursorUpdate = dispatch(event)
                if (eventName == RealtimeSocketEvents.SyncResumeResponse && event is Heartbeat) {
                    requestResumeAfterProjectionRejection()
                }
                val shouldFanOutRaw = shouldFanOutRawEvent(eventName, cursorUpdate.serverAhead)
                if (shouldFanOutRaw) {
                    val connectionContext = currentConnectionContext ?: return@on
                    rawListeners.toList().forEach { it(connectionContext, eventName, payload) }
                }
                successDiagnostics.record(
                    eventName = eventName,
                    mappedType = event.realtimeDiagnosticName(),
                    parseDurationMs = mapDurationMs,
                    appliedSeq = cursor.appliedLastSeq,
                    serverMaxSeq = cursor.serverMaxSeq,
                    listenerCount = listeners.size,
                    rawFanOut = shouldFanOutRaw,
                    serverId = connectedServerId.orEmpty()
                ).forEach(logger)
            }
        }
    }

    private fun shouldFanOutRawEvent(eventName: String, serverAhead: Boolean): Boolean =
        eventName != RealtimeSocketEvents.Heartbeat || serverAhead

    private fun emitRoomPayload(eventName: String, payload: String) {
        transport.emit(eventName, mapOf("channelId" to payload))
    }

    private fun rejoinChannels(reason: String) {
        joinedChannels.forEach { channelId ->
            logger("join_room channelId=${channelId.value} replay=true reason=$reason")
            emitRoomPayload(RealtimeSocketEvents.JoinChannel, channelId.value)
        }
    }

    private fun rebaselineCursor(reason: String) {
        val nextEpoch = "${reason}-${clockMillis()}"
        val update = RealtimeCursorPolicy.rebaseline(cursor, nextEpoch)
        cursor = update.state
        resumeProjectionRetryLastSeq = null
        logger(
            "cursor_rebaseline reason=$reason serverId=${connectedServerId.orEmpty()} " +
                "epoch=${cursor.epoch} rebaselineRequired=${update.rebaselineRequired}"
        )
    }

    private fun emitSyncResume(reason: String) {
        val resumeLastSeq = cursor.resumeLastSeq
        if (resumeLastSeq <= 0) return
        logger("sync_resume_emit serverId=${connectedServerId.orEmpty()} lastSeq=$resumeLastSeq reason=$reason")
        transport.emit(RealtimeSocketEvents.SyncResume, RealtimeResumeRequest(resumeLastSeq).toWireMap())
    }

    private fun requestResumeAfterProjectionRejection() {
        val resumeLastSeq = cursor.resumeLastSeq
        if (resumeLastSeq <= 0L) {
            logger("sync_resume_recovery_skip reason=no_applied_cursor")
            return
        }
        if (resumeProjectionRetryLastSeq == resumeLastSeq) {
            logger("sync_resume_recovery_escalate reason=repeat_projection_rejection lastSeq=$resumeLastSeq")
            dispatch(
                RealtimeRefreshRequired(
                    scope = RealtimeRefreshScope.Home,
                    reason = "sync_resume_projection_rejected_repeatedly",
                    serverId = connectedServerId?.let(::ServerId),
                )
            )
            return
        }
        resumeProjectionRetryLastSeq = resumeLastSeq
        emitSyncResume(reason = "projection_rejected")
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
        val connectionContext = currentConnectionContext ?: return
        transport.connect(next.toWireMap(), connectionContext)
    }

    private fun dispatch(event: RealtimeEvent): RealtimeCursorUpdate {
        val previousAppliedSeq = cursor.appliedLastSeq
        val cursorUpdate = RealtimeCursorPolicy.updateFromEvent(cursor, event)
        cursor = cursorUpdate.state
        if (cursor.appliedLastSeq > previousAppliedSeq) {
            resumeProjectionRetryLastSeq = null
        }
        if (event is Heartbeat && cursorUpdate.serverAhead) {
            logger(
                "heartbeat_gap_detected serverId=${connectedServerId.orEmpty()} " +
                    "serverSeq=${event.seq} appliedSeq=${cursor.appliedLastSeq}"
            )
        }
        diagnostics?.onEvent(event, connectedServerId?.let(::ServerId), visibleThreadId = null)
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

internal class RealtimeSuccessDiagnostics(
    private val clockMillis: () -> Long,
    private val summaryEventCount: Int = 64,
    private val summaryIntervalMillis: Long = 30_000L,
    private val slowParseMillis: Long = 16L
) {
    private var totalCount = 0L
    private var pendingCount = 0
    private var pendingRawFanOutCount = 0
    private var maxParseDurationMs = 0L
    private var lastSummaryAtMillis = clockMillis()
    private val pendingTypes = linkedMapOf<String, Int>()

    fun record(
        eventName: String,
        mappedType: String,
        parseDurationMs: Long,
        appliedSeq: Long,
        serverMaxSeq: Long,
        listenerCount: Int,
        rawFanOut: Boolean,
        serverId: String
    ): List<String> {
        totalCount += 1
        pendingCount += 1
        if (rawFanOut) pendingRawFanOutCount += 1
        maxParseDurationMs = maxOf(maxParseDurationMs, parseDurationMs)
        val normalizedType = mappedType.ifBlank { "unknown" }
        pendingTypes[normalizedType] = pendingTypes.getOrElse(normalizedType) { 0 } + 1

        val logs = mutableListOf<String>()
        if (parseDurationMs >= slowParseMillis) {
            logs +=
                "event_parse_slow eventName=$eventName mapped=$normalizedType " +
                    "durationMs=$parseDurationMs serverId=$serverId"
        }
        val now = clockMillis()
        val intervalElapsed = (now - lastSummaryAtMillis).coerceAtLeast(0L) >= summaryIntervalMillis
        if (totalCount == 1L || pendingCount >= summaryEventCount || intervalElapsed) {
            val typeSummary =
                pendingTypes.entries
                    .sortedByDescending { it.value }
                    .take(MaxSummaryTypes)
                    .joinToString("|") { (type, count) -> "$type:$count" }
            logs +=
                "event_success_summary count=$pendingCount total=$totalCount rawFanOut=$pendingRawFanOutCount " +
                    "maxParseMs=$maxParseDurationMs appliedSeq=$appliedSeq serverMaxSeq=$serverMaxSeq " +
                    "listeners=$listenerCount serverId=$serverId lastEventName=$eventName types=$typeSummary"
            pendingCount = 0
            pendingRawFanOutCount = 0
            maxParseDurationMs = 0L
            pendingTypes.clear()
            lastSummaryAtMillis = now
        }
        return logs
    }

    private companion object {
        const val MaxSummaryTypes = 6
    }
}

private fun RealtimeAuth.toWireMap(): Map<String, String> =
    mapOf(
        "token" to token,
        "serverId" to serverId.value,
        // task #459/#103: platform-scoped event delivery — the server filters
        // platform-scoped events (e.g. notification:push) by this declaration.
        // Closed set web|mobile|desktop|cli; undeclared sockets default to web.
        "clientKind" to "mobile"
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
    is PerReceiverReadStateChanged -> "read_state_updated"
    is PerReceiverNotificationPrefsChanged -> "notification_prefs_updated"
    is PerReceiverPinnedChanged -> "pinned_updated"
    is NotificationPushReceived -> "notification_push"
}
