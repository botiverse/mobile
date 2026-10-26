package build.raft.app.desktop.realtime

import build.raft.app.desktop.RaftDesktopLog
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.RAFT_SOCKET_BASE_URL
import build.raft.app.data.realtime.RealtimeAuth
import build.raft.app.data.realtime.RealtimeBaselineHistoryRequests
import build.raft.app.data.realtime.RealtimeSyncHorizon
import build.raft.app.data.realtime.RealtimeConnectRequest
import build.raft.app.data.realtime.RealtimeSocketConnectionContext
import build.raft.app.data.realtime.RealtimeConnectionState
import build.raft.app.data.realtime.RealtimeClientDiagnostics
import build.raft.app.data.realtime.RealtimeCursorPolicy
import build.raft.app.data.realtime.RealtimeCursorState
import build.raft.app.data.realtime.RealtimeCursorUpdate
import build.raft.app.data.realtime.RealtimeHeartbeatWatchdog
import build.raft.app.data.realtime.RealtimeHeartbeatWatchdogSnapshot
import build.raft.app.data.realtime.RealtimeLocalEvents
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
import build.raft.app.model.ThreadFollowersUpdated
import build.raft.app.model.ScopeReadUpdated
import build.raft.app.model.AgentTypingUpdated
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
import build.raft.app.model.ComputerRestartDone
import build.raft.app.model.ServerNotificationPreferencesChanged
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
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val watchdogScheduler: ((tick: () -> Unit) -> (() -> Unit)) = { tick ->
        val timer = Timer("slock-realtime-heartbeat-watchdog", true)
        timer.scheduleAtFixedRate(
            object : TimerTask() {
                override fun run() = tick()
            },
            RealtimeHeartbeatWatchdog.CHECK_INTERVAL_MILLIS,
            RealtimeHeartbeatWatchdog.CHECK_INTERVAL_MILLIS,
        )
        timer::cancel
    },
    private val beforeHeartbeatReconnect: (() -> Unit)? = null,
    // Ticket 9222ba03: socket callbacks run under the transport lock, and the auth provider may
    // do a network token refresh (4s per attempt, one retry). Running it there made onResume's
    // setReconnectAllowed wait on the lock long enough to ANR, so the provider runs here instead.
    private val authRefreshRunner: (task: () -> Unit) -> Unit = ::runOnRealtimeAuthRefreshThread,
    // Sync horizon (slock #10982): whether a baseline history read of this server was already sent, which
    // makes a starting horizon unusable (see RealtimeSyncHorizon).
    private val baselineHistorySent: (serverId: String) -> Boolean = RealtimeBaselineHistoryRequests::anySent,
) {
    private val listeners = mutableSetOf<(RealtimeEvent) -> Unit>()
    private val rawListeners = mutableSetOf<(RealtimeSocketConnectionContext, String, Any?) -> Unit>()
    private val connectedListeners = mutableSetOf<(RealtimeSocketConnectionContext) -> Unit>()
    private val roomsJoinedListeners = mutableSetOf<(RealtimeSocketConnectionContext) -> Unit>()
    private val channelJoinAckListeners = mutableSetOf<(RealtimeSocketConnectionContext, String, Boolean) -> Unit>()
    private val transportSubscriptions = mutableListOf<RealtimeSubscription>()
    private val joinedChannels = linkedSetOf<ChannelId>()
    private var cursor: RealtimeCursorState = RealtimeCursorPolicy.fromAppliedSeq(0)
    private var connectedServerId: String? = null
    private var currentAuth: RealtimeAuth? = null
    private var currentConnectionContext: RealtimeSocketConnectionContext? = null
    private var authRefreshProvider: ((String, RealtimeAuth?) -> RealtimeAuth?)? = null
    private var lastHeartbeatAtMillis: Long? = null
    private var lastInboundEventAtMillis: Long? = null
    private var resumeProjectionRetryLastSeq: Long? = null
    // Set when a reconnect could not keep a resume cursor; the catch-up runs on that socket's rooms:joined.
    private var pendingCatchUpReason: String? = null
    private var nextWatchdogEpoch = 0L
    private var watchdogRegistration: WatchdogRegistration? = null
    private var reconnectAllowed = true
    private val successDiagnostics = RealtimeSuccessDiagnostics(clockMillis)
    private var connectionState: RealtimeConnectionState = RealtimeConnectionState.Disconnected

    val state: RealtimeConnectionState
        get() = transport.linearize { connectionState }

    fun setAuthRefreshProvider(provider: ((String, RealtimeAuth?) -> RealtimeAuth?)?) {
        transport.linearize { authRefreshProvider = provider }
    }

    fun setReconnectAllowed(allowed: Boolean) {
        transport.linearize {
            if (reconnectAllowed == allowed) return@linearize
            reconnectAllowed = allowed
            if (!allowed) {
                stopHeartbeatWatchdog()
                currentConnectionContext = null
                connectionState = RealtimeConnectionState.Disconnected
                transport.disconnect()
                diagnostics?.onIntentionalDisconnect(
                    reason = "reconnect_suspended",
                    serverId = connectedServerId?.let(::ServerId),
                )
            }
        }
    }

    fun connect(request: RealtimeConnectRequest) {
        transport.linearize {
            val sameServer = connectedServerId == request.auth.serverId.value
            if (!sameServer) {
                joinedChannels.clear()
                connectedServerId = request.auth.serverId.value
            }
            currentAuth = request.auth
            val connectionContext = RealtimeSocketConnectionContext(request.auth.serverId, request.witness)
            currentConnectionContext = connectionContext
            cursor = RealtimeCursorPolicy.connect(cursor, request.lastSeq, sameServer)
            resumeProjectionRetryLastSeq = null
            pendingCatchUpReason = null
            if (!reconnectAllowed) {
                connectionState = RealtimeConnectionState.Disconnected
                logger("client_connect_skip reason=reconnect_suspended serverId=${request.auth.serverId.value}")
                return@linearize
            }
            connectionState = RealtimeConnectionState.Connecting
            lastHeartbeatAtMillis = null
            lastInboundEventAtMillis = null
            diagnostics?.onConnectRequested(request.auth.serverId, request.lastSeq)
            ensureTransportListeners()
            restartHeartbeatWatchdog()
            logger("client_connect_start serverId=${request.auth.serverId.value} lastSeq=${request.lastSeq}")
            transport.connect(request.auth.toWireMap(), connectionContext)
        }
    }

    fun disconnect() {
        transport.linearize {
            val disconnectedServerId = connectedServerId
            logger("client_disconnect serverId=${disconnectedServerId.orEmpty()} joinedRooms=${joinedChannels.size}")
            stopHeartbeatWatchdog()
            currentConnectionContext = null
            currentAuth = null
            connectedServerId = null
            joinedChannels.clear()
            cursor = RealtimeCursorPolicy.fromAppliedSeq(0L)
            resumeProjectionRetryLastSeq = null
            pendingCatchUpReason = null
            connectionState = RealtimeConnectionState.Disconnected
            transport.disconnect()
            diagnostics?.onIntentionalDisconnect(
                reason = "client_disconnect",
                serverId = disconnectedServerId?.let(::ServerId),
            )
        }
    }

    fun checkHeartbeatSilence(nowMillis: Long = clockMillis()): Boolean =
        transport.linearize { checkHeartbeatSilenceLocked(nowMillis) }

    private fun checkHeartbeatSilenceLocked(nowMillis: Long): Boolean {
        if (!reconnectAllowed) return false
        val shouldReconnect =
            RealtimeHeartbeatWatchdog.shouldForceReconnect(
                RealtimeHeartbeatWatchdogSnapshot(
                    connected = connectionState == RealtimeConnectionState.Connected ||
                        connectionState == RealtimeConnectionState.RoomsJoined,
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
        connectionState = RealtimeConnectionState.Connecting
        lastHeartbeatAtMillis = null
        lastInboundEventAtMillis = nowMillis
        reconnectCursor(reason = "heartbeat_silent", serverId = connectionContext.serverId)
        beforeHeartbeatReconnect?.invoke()
        transport.connect(auth.toWireMap(), connectionContext)
        return true
    }

    fun joinChannel(channelId: ChannelId) {
        transport.linearize {
            val added = joinedChannels.add(channelId)
            logger("join_room channelId=${channelId.value} replay=false added=$added")
            if (!added) return@linearize
            emitRoomPayload(RealtimeSocketEvents.JoinChannel, channelId.value)
        }
    }

    fun leaveChannel(channelId: ChannelId) {
        transport.linearize {
            val removed = joinedChannels.remove(channelId)
            logger("leave_room channelId=${channelId.value} removed=$removed")
            if (!removed) return@linearize
            emitRoomPayload(RealtimeSocketEvents.LeaveChannel, channelId.value)
        }
    }

    fun emitRoomCommand(eventName: String, payload: String, force: Boolean = false) {
        transport.linearize {
            when (eventName) {
                RealtimeSocketEvents.JoinChannel -> {
                    val channelId = ChannelId(payload)
                    val added = joinedChannels.add(channelId)
                    logger("join_room channelId=${channelId.value} replay=false added=$added force=$force")
                    if (!added && !force) return@linearize
                    emitRoomPayload(eventName, channelId.value)
                }
                RealtimeSocketEvents.LeaveChannel -> {
                    val channelId = ChannelId(payload)
                    val removed = joinedChannels.remove(channelId)
                    logger("leave_room channelId=${channelId.value} removed=$removed")
                    if (!removed) return@linearize
                    emitRoomPayload(eventName, channelId.value)
                }
                else -> emitRoomPayload(eventName, payload)
            }
        }
    }

    fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription {
        transport.linearize { listeners += listener }
        return RealtimeSubscription { transport.linearize { listeners -= listener } }
    }

    fun observeRawEvents(
        listener: (context: RealtimeSocketConnectionContext, eventName: String, rawPayload: Any?) -> Unit
    ): RealtimeSubscription {
        transport.linearize { rawListeners += listener }
        return RealtimeSubscription { transport.linearize { rawListeners -= listener } }
    }

    /**
     * Task #167: called on the transport's linearized thread each time a socket connects, before any frame of
     * that socket is dispatched, so a consumer can mark the start of a new connection.
     */
    fun observeConnected(listener: (context: RealtimeSocketConnectionContext) -> Unit): RealtimeSubscription {
        transport.linearize { connectedListeners += listener }
        return RealtimeSubscription { transport.linearize { connectedListeners -= listener } }
    }

    /**
     * Task #168: called on the transport's linearized thread when the server sends `rooms:joined` for the
     * current socket (it has subscribed the connection to all of its rooms), after that socket's connected
     * listeners and in order with its frames.
     */
    fun observeRoomsJoined(listener: (context: RealtimeSocketConnectionContext) -> Unit): RealtimeSubscription {
        transport.linearize { roomsJoinedListeners += listener }
        return RealtimeSubscription { transport.linearize { roomsJoinedListeners -= listener } }
    }

    /**
     * Task #14c: called on the transport's linearized thread when the server answers a `join:channel` of the
     * current socket: `channel:joined { channelId }` (joined = true: the socket is now in `channel:<id>`) or
     * `channel:join_denied { channelId }` (false). In order with that socket's frames, after its connected
     * listeners. A payload without a channel id is dropped.
     */
    fun observeChannelJoinAck(
        listener: (context: RealtimeSocketConnectionContext, channelId: String, joined: Boolean) -> Unit,
    ): RealtimeSubscription {
        transport.linearize { channelJoinAckListeners += listener }
        return RealtimeSubscription { transport.linearize { channelJoinAckListeners -= listener } }
    }

    private fun ensureTransportListeners() {
        if (transportSubscriptions.isNotEmpty()) return
        transportSubscriptions += transport.on(RealtimeSocketEvents.Connect) { capturedContext, _ ->
            if (!acceptConnectionCallback(capturedContext, RealtimeSocketEvents.Connect)) return@on
            markInbound()
            startHeartbeatWatchdog()
            connectionState = RealtimeConnectionState.Connected
            connectedListeners.toList().forEach { it(capturedContext) }
            diagnostics?.onConnected(capturedContext.serverId)
            logger(
                "client_connected serverId=${capturedContext.serverId.value} joinedRooms=${joinedChannels.size} " +
                    "appliedSeq=${cursor.appliedLastSeq} serverMaxSeq=${cursor.serverMaxSeq}"
            )
            requestStartingHorizon(capturedContext)
            rejoinChannels(reason = "connect")
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.RoomsJoined) { capturedContext, _ ->
            if (!acceptConnectionCallback(capturedContext, RealtimeSocketEvents.RoomsJoined)) return@on
            markInbound()
            connectionState = RealtimeConnectionState.RoomsJoined
            roomsJoinedListeners.toList().forEach { it(capturedContext) }
            diagnostics?.onRoomsJoined(
                capturedContext.serverId,
                cursor.resumeLastSeq.takeIf { it > 0L }
            )
            logger(
                "rooms_joined serverId=${capturedContext.serverId.value} joinedRooms=${joinedChannels.size} " +
                    "appliedSeq=${cursor.appliedLastSeq} serverMaxSeq=${cursor.serverMaxSeq}"
            )
            emitSyncResume(reason = "rooms_joined")
            pendingCatchUpReason?.let { reason ->
                pendingCatchUpReason = null
                requestCatchUp(capturedContext, reason = "${reason}_resume_unavailable")
            }
        }
        listOf(RealtimeSocketEvents.ChannelJoined to true, RealtimeSocketEvents.ChannelJoinDenied to false)
            .forEach { (eventName, joined) ->
                transportSubscriptions += transport.on(eventName) { capturedContext, payload ->
                    if (!acceptConnectionCallback(capturedContext, eventName)) return@on
                    markInbound()
                    val channelId = payload.stringFieldOrNull("channelId")
                    logger(
                        "channel_join_ack serverId=${capturedContext.serverId.value} " +
                            "channelId=${channelId.orEmpty()} joined=$joined"
                    )
                    if (channelId == null) return@on
                    channelJoinAckListeners.toList().forEach { it(capturedContext, channelId, joined) }
                }
            }
        transportSubscriptions += transport.on(RealtimeSocketEvents.ConnectError) { capturedContext, payload ->
            if (!acceptConnectionCallback(capturedContext, RealtimeSocketEvents.ConnectError)) return@on
            markInbound()
            connectionState = RealtimeConnectionState.Failed(payload?.toString().orEmpty())
            diagnostics?.onConnectionFailed(
                    reason = payload?.toString().orEmpty(),
                    serverId = capturedContext.serverId,
            )
            logger("client_connect_error serverId=${capturedContext.serverId.value} reason=${payload?.javaClass?.simpleName.orEmpty()}")
            if (payload.isAuthFailurePayload()) {
                refreshTransportAuth(
                    reason = "connect_error",
                    capturedContext = capturedContext,
                )
            }
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.Disconnect) { capturedContext, _ ->
            if (!acceptConnectionCallback(capturedContext, RealtimeSocketEvents.Disconnect)) return@on
            stopHeartbeatWatchdog()
            connectionState = RealtimeConnectionState.Disconnected
            diagnostics?.onDisconnected(
                    reason = "transport_disconnect",
                    serverId = capturedContext.serverId,
            )
            logger("client_disconnected serverId=${capturedContext.serverId.value}")
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.Reconnect) { capturedContext, _ ->
            if (!acceptConnectionCallback(capturedContext, RealtimeSocketEvents.Reconnect)) return@on
            markInbound()
            startHeartbeatWatchdog()
            connectionState = RealtimeConnectionState.Connected
            reconnectCursor(reason = "transport_reconnect", serverId = capturedContext.serverId)
            diagnostics?.onConnected(capturedContext.serverId)
            logger(
                "client_reconnected serverId=${capturedContext.serverId.value} joinedRooms=${joinedChannels.size} " +
                    "appliedSeq=${cursor.appliedLastSeq} serverMaxSeq=${cursor.serverMaxSeq}"
            )
            rejoinChannels(reason = "reconnect")
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.ReconnectAttempt) { capturedContext, payload ->
            if (!acceptConnectionCallback(capturedContext, RealtimeSocketEvents.ReconnectAttempt)) return@on
            refreshTransportAuth(
                reason = "reconnect_attempt",
                capturedContext = capturedContext,
            )
            logger("client_reconnect_attempt serverId=${capturedContext.serverId.value} payload=${payload?.toString().orEmpty()}")
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.ReconnectError) { capturedContext, payload ->
            if (!acceptConnectionCallback(capturedContext, RealtimeSocketEvents.ReconnectError)) return@on
            diagnostics?.onConnectionFailed(
                    reason = payload?.toString().orEmpty(),
                    serverId = capturedContext.serverId,
            )
            logger("client_reconnect_error serverId=${capturedContext.serverId.value} reason=${payload?.javaClass?.simpleName.orEmpty()}")
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.ReconnectFailed) { capturedContext, payload ->
            if (!acceptConnectionCallback(capturedContext, RealtimeSocketEvents.ReconnectFailed)) return@on
            stopHeartbeatWatchdog()
            connectionState = RealtimeConnectionState.Failed(payload?.toString().orEmpty())
            diagnostics?.onConnectionFailed(
                    reason = payload?.toString().orEmpty(),
                    serverId = capturedContext.serverId,
            )
            logger("client_reconnect_failed serverId=${capturedContext.serverId.value} reason=${payload?.javaClass?.simpleName.orEmpty()}")
        }
        RealtimeWireEventNames.forEach { eventName ->
            transportSubscriptions += transport.on(eventName) { capturedContext, payload ->
                if (!acceptConnectionCallback(capturedContext, eventName)) return@on
                markInbound(eventName)
                val payloadServerId = payload.serverIdOrNull()
                val capturedServerId = capturedContext.serverId.value
                if (payloadServerId != null && payloadServerId != capturedServerId) {
                    logger(
                        "event_skip reason=server_mismatch eventName=$eventName payloadServerId=$payloadServerId connectedServerId=$capturedServerId payloadType=${payload?.javaClass?.simpleName.orEmpty()}"
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
                        rawListeners.toList().forEach { it(capturedContext, eventName, payload) }
                    }
                    return@on
                }
                val cursorUpdate = dispatch(event)
                if (eventName == RealtimeSocketEvents.SyncResumeResponse && event is Heartbeat) {
                    requestResumeAfterProjectionRejection(capturedContext)
                }
                if (event is SyncResumeResponse) {
                    logger(
                        "sync_resume_response serverId=$capturedServerId currentSeq=${event.currentSeq} " +
                            "messages=${event.messages.size} hasMore=${event.hasMore} " +
                            "horizon=${event.horizon != null} horizonReset=${event.horizonReset}"
                    )
                }
                val shouldFanOutRaw = shouldFanOutRawEvent(eventName, cursorUpdate.serverAhead)
                if (shouldFanOutRaw) {
                    rawListeners.toList().forEach { it(capturedContext, eventName, payload) }
                }
                if (event is SyncResumeResponse && event.horizonReset) {
                    // Too many late messages to send: the rows above were applied, the late ones were not.
                    // Home/Activity re-fetch and the open thread re-reads its latest page.
                    requestCatchUp(capturedContext, reason = RealtimeSyncHorizon.ResetReason)
                }
                successDiagnostics.record(
                    eventName = eventName,
                    mappedType = event.realtimeDiagnosticName(),
                    parseDurationMs = mapDurationMs,
                    appliedSeq = cursor.appliedLastSeq,
                    serverMaxSeq = cursor.serverMaxSeq,
                    listenerCount = listeners.size,
                    rawFanOut = shouldFanOutRaw,
                    serverId = capturedServerId,
                ).forEach(logger)
            }
        }
    }

    private fun acceptConnectionCallback(
        capturedContext: RealtimeSocketConnectionContext,
        eventName: String,
    ): Boolean {
        val accepted = reconnectAllowed && capturedContext === currentConnectionContext
        if (!accepted) {
            logger(
                "client_event_skip eventName=$eventName result=stale_or_suspended " +
                    "serverId=${capturedContext.serverId.value}"
            )
        }
        return accepted
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

    /**
     * A new socket replaces a dropped one of the same connection context (transport auto-reconnect,
     * heartbeat-silence reconnect). #255 rotates the cursor epoch here; the applied watermark is kept
     * for the same server so that socket's `rooms:joined` replays the gap with `sync:resume`, as web
     * and iOS do. Without a watermark the gap cannot be replayed and Home/Activity re-fetch instead.
     */
    private fun reconnectCursor(reason: String, serverId: ServerId) {
        val nextEpoch = "${reason}-${clockMillis()}"
        val plan =
            RealtimeCursorPolicy.reconnect(
                state = cursor,
                nextEpoch = nextEpoch,
                sameServer = serverId.value == connectedServerId,
            )
        cursor = plan.state
        resumeProjectionRetryLastSeq = null
        pendingCatchUpReason = if (plan.catchUpRequired) reason else null
        logger(
            "cursor_rebaseline reason=$reason serverId=${connectedServerId.orEmpty()} " +
                "epoch=${cursor.epoch} resumeLastSeq=${plan.resumeLastSeq} catchUpRequired=${plan.catchUpRequired}"
        )
    }

    /**
     * Missed events cannot be replayed: ask Home and Activity to re-fetch. The typed event reaches the
     * app-scope owner; shared stores only consume the raw path, so the same request also goes there
     * as a mobile-local frame (mapped to `RealtimeRefreshRequired(Home)` by the shared mapper).
     */
    private fun requestCatchUp(capturedContext: RealtimeSocketConnectionContext, reason: String) {
        val serverId = capturedContext.serverId
        logger("realtime_catch_up_request serverId=${serverId.value} reason=$reason")
        dispatch(
            RealtimeRefreshRequired(
                scope = RealtimeRefreshScope.Home,
                reason = reason,
                serverId = serverId,
            )
        )
        val payload = JSONObject().put("serverId", serverId.value).put("reason", reason).toString()
        rawListeners.toList().forEach { it(capturedContext, RealtimeLocalEvents.CatchUpRequired, payload) }
    }

    private fun emitSyncResume(reason: String) {
        val resumeLastSeq = cursor.resumeLastSeq
        if (resumeLastSeq <= 0) return
        // The held horizon (see RealtimeCursorState.horizon for its invariant) starts this round; the
        // response's horizon starts the next one. Without one the resume is exactly the pre-horizon one.
        val sinceHorizon = cursor.horizon
        logger(
            "sync_resume_emit serverId=${connectedServerId.orEmpty()} lastSeq=$resumeLastSeq " +
                "sinceHorizon=${sinceHorizon != null} reason=$reason"
        )
        transport.emit(
            RealtimeSocketEvents.SyncResume,
            RealtimeResumeRequest(resumeLastSeq, sinceHorizon = sinceHorizon).toWireMap(),
        )
    }

    /**
     * Asks the new socket for a starting horizon while none is held. Never waited for: the ack is used
     * only if it arrives before any baseline history request of this server was sent
     * ([RealtimeCursorPolicy.acceptStartingHorizon]); a late, missing (older server) or error ack
     * leaves no horizon, so the first resume runs as before and its response provides the next one.
     */
    private fun requestStartingHorizon(capturedContext: RealtimeSocketConnectionContext) {
        if (cursor.horizon != null) return
        val sent =
            transport.emitWithAck(RealtimeSocketEvents.SyncHorizon) { ackContext, payload ->
                transport.linearize { onStartingHorizonAck(ackContext, payload) }
            }
        logger(
            "sync_horizon_request serverId=${capturedContext.serverId.value} " +
                "result=${if (sent) "sent" else "unsupported_transport"}"
        )
    }

    private fun onStartingHorizonAck(capturedContext: RealtimeSocketConnectionContext, payload: Any?) {
        if (!acceptConnectionCallback(capturedContext, RealtimeSocketEvents.SyncHorizon)) return
        val serverId = capturedContext.serverId.value
        val decision =
            RealtimeCursorPolicy.acceptStartingHorizon(
                state = cursor,
                horizon = payload.syncHorizonAckOrNull(),
                baselineHistorySent = baselineHistorySent(serverId),
            )
        cursor = decision.state
        logger("sync_horizon_ack serverId=$serverId accepted=${decision.accepted} reason=${decision.reason}")
    }

    private fun requestResumeAfterProjectionRejection(capturedContext: RealtimeSocketConnectionContext) {
        val resumeLastSeq = cursor.resumeLastSeq
        if (resumeLastSeq <= 0L) {
            logger("sync_resume_recovery_skip reason=no_applied_cursor")
            return
        }
        if (resumeProjectionRetryLastSeq == resumeLastSeq) {
            logger("sync_resume_recovery_escalate reason=repeat_projection_rejection lastSeq=$resumeLastSeq")
            requestCatchUp(capturedContext, reason = "sync_resume_projection_rejected_repeatedly")
            return
        }
        resumeProjectionRetryLastSeq = resumeLastSeq
        emitSyncResume(reason = "projection_rejected")
    }

    private fun startHeartbeatWatchdog() {
        if (watchdogRegistration != null) return
        nextWatchdogEpoch += 1L
        val epoch = nextWatchdogEpoch
        val cancel =
            watchdogScheduler {
                transport.linearize {
                    if (watchdogRegistration?.epoch != epoch) return@linearize
                    checkHeartbeatSilenceLocked(clockMillis())
                }
            }
        watchdogRegistration = WatchdogRegistration(epoch, cancel)
    }

    private fun restartHeartbeatWatchdog() {
        stopHeartbeatWatchdog()
        startHeartbeatWatchdog()
    }

    private fun stopHeartbeatWatchdog() {
        val registration = watchdogRegistration
        watchdogRegistration = null
        registration?.cancel?.invoke()
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

    private fun refreshTransportAuth(
        reason: String,
        capturedContext: RealtimeSocketConnectionContext,
    ) {
        val provider = authRefreshProvider ?: return
        if (!reconnectAllowed) {
            logger("auth_refresh_skip reason=$reason result=reconnect_suspended")
            return
        }
        val activeContext = currentConnectionContext
        if (capturedContext !== activeContext) {
            logger(
                "auth_refresh_skip reason=$reason result=stale_connection " +
                    "capturedServerId=${capturedContext.serverId.value} " +
                    "activeServerId=${activeContext?.serverId?.value.orEmpty()}"
            )
            return
        }
        val previous = currentAuth
        authRefreshRunner {
            val next = provider(reason, previous)
            transport.linearize {
                applyRefreshedTransportAuth(reason, capturedContext, previous, next)
            }
        }
    }

    private fun applyRefreshedTransportAuth(
        reason: String,
        capturedContext: RealtimeSocketConnectionContext,
        previous: RealtimeAuth?,
        next: RealtimeAuth?,
    ) {
        // The lock was released while the provider ran: the connection may have been replaced,
        // suspended, or re-authenticated meanwhile, and none of those may be overwritten.
        if (!reconnectAllowed) {
            logger("auth_refresh_skip reason=$reason result=reconnect_suspended")
            return
        }
        if (capturedContext !== currentConnectionContext || currentAuth != previous) {
            logger(
                "auth_refresh_skip reason=$reason result=stale_connection " +
                    "capturedServerId=${capturedContext.serverId.value} " +
                    "activeServerId=${currentConnectionContext?.serverId?.value.orEmpty()}"
            )
            return
        }
        if (next == null) {
            logger("auth_refresh_skip reason=$reason result=missing_session")
            return
        }
        if (previous == next) {
            logger("auth_refresh_skip reason=$reason result=unchanged serverId=${next.serverId.value}")
            return
        }
        val previousContext = currentConnectionContext ?: run {
            logger("auth_refresh_skip reason=$reason result=missing_connection")
            return
        }
        val serverChanged = previous?.serverId != null && previous.serverId != next.serverId
        if (serverChanged) {
            joinedChannels.clear()
            cursor = RealtimeCursorPolicy.fromAppliedSeq(0L)
            resumeProjectionRetryLastSeq = null
            pendingCatchUpReason = null
        }
        currentAuth = next
        connectedServerId = next.serverId.value
        logger("auth_refresh_apply reason=$reason serverId=${next.serverId.value} tokenChanged=${previous?.token != next.token}")
        connectionState = RealtimeConnectionState.Connecting
        val nextContext =
            RealtimeSocketConnectionContext(
                serverId = next.serverId,
                witness = previousContext.witness,
            )
        currentConnectionContext = nextContext
        transport.connect(next.toWireMap(), nextContext)
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

    private data class WatchdogRegistration(
        val epoch: Long,
        val cancel: () -> Unit,
    )

    companion object {
        const val DEFAULT_SOCKET_URL = RAFT_SOCKET_BASE_URL

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

    /** `sync:horizon` ack `{ horizon }`: only a string horizon counts (never a number). */
    private fun Any?.syncHorizonAckOrNull(): String? = when (this) {
        is JSONObject -> RealtimeSyncHorizon.parse(opt(RealtimeSyncHorizon.HorizonKey) as? String)
        is JsonObject ->
            get(RealtimeSyncHorizon.HorizonKey)
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.let(RealtimeSyncHorizon::parse)
        is Map<*, *> -> RealtimeSyncHorizon.parse(this[RealtimeSyncHorizon.HorizonKey] as? String)
        else -> null
    }

    /** Task #14c: a top-level non-blank string field of a socket payload (exact camelCase key, no aliases). */
    private fun Any?.stringFieldOrNull(key: String): String? = when (this) {
        null -> null
        is JSONObject -> (opt(key) as? String)?.takeIf(String::isNotBlank)
        is JsonObject ->
            get(key)
                ?.takeIf { it.isJsonPrimitive }
                ?.asJsonPrimitive
                ?.takeIf { it.isString }
                ?.asString
                ?.takeIf(String::isNotBlank)
        is Map<*, *> -> (this[key] as? String)?.takeIf(String::isNotBlank)
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
    is ThreadFollowersUpdated -> "thread_followers_updated"
    is ScopeReadUpdated -> "scope_read_updated"
    is AgentTypingUpdated -> "agent_typing"
        is ThreadUpdated -> "thread_updated"
    is TaskCreated -> "task_created"
    is TaskUpdated -> "task_updated"
    is TaskDeleted -> "task_deleted"
    is MemberPresenceUpdated -> "member_presence_updated"
    is RealtimeRefreshRequired -> "refresh_required"
    is ServerMembershipRemoved -> "server_membership_removed"
    is ServerDeleted -> "server_deleted"
    is ServerPlanUpdated -> "server_plan_updated"
    is ComputerRestartDone -> "computer_restart_done"
    is ServerNotificationPreferencesChanged -> "server_notification_prefs_updated"
    is AgentCreated -> "agent_created"
    is SyncResumeResponse -> "sync_resume_response"
    is Heartbeat -> "heartbeat"
    is PerReceiverReadStateChanged -> "read_state_updated"
    is PerReceiverNotificationPrefsChanged -> "notification_prefs_updated"
    is PerReceiverPinnedChanged -> "pinned_updated"
    is NotificationPushReceived -> "notification_push"
}

private val realtimeAuthRefreshExecutor =
    java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "slock-realtime-auth-refresh").apply { isDaemon = true }
    }

private fun runOnRealtimeAuthRefreshThread(task: () -> Unit) {
    realtimeAuthRefreshExecutor.execute {
        try {
            task()
        } catch (throwable: Throwable) {
            RaftDesktopLog.w(
                "SlockRealtime",
                "auth_refresh_failed error=${throwable.javaClass.simpleName}",
            )
        }
    }
}
