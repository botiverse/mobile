package build.raft.app.data.realtime

import build.raft.app.data.DataResult
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.SlockEnhancedNotifyCenter
import build.raft.app.data.SlockEnhancedNotifyEvent
import build.raft.app.data.SlockEnhancedNotifyEventBus
import build.raft.app.model.ChannelId
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.MessageNew
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import build.raft.app.optString

interface SlockRealtimeAppScopeConnection {
    val state: RealtimeConnectionState

    suspend fun connect(
        serverId: ServerId,
        accessToken: String,
        lastSeq: Long,
        witness: RealtimeConnectionWitnessToken,
    ): DataResult<Unit>
    fun disconnect()
    fun emitRoomCommand(eventName: String, payload: String, force: Boolean = false)
    fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription
    /**
     * [transportEpoch] (task #168): the epoch of the socket that delivered the frame, bound on the socket
     * thread before the frame enters any queue; null when that socket posted no transport signal.
     */
    fun observeRawEvents(
        listener: (
            context: RealtimeSocketConnectionContext,
            eventName: String,
            rawPayload: String,
            transportEpoch: Long?,
        ) -> Unit
    ): RealtimeSubscription
}

interface SlockRealtimeAppScopeSink {
    fun log(message: String)
    fun fanOutRawRealtimeEvent(
        serverId: ServerId,
        witness: RealtimeConnectionWitnessToken,
        eventName: String,
        rawPayload: String,
        transportEpoch: Long?,
    )
    suspend fun maybePostBackgroundUnreadNotification(
        serverId: ServerId,
        event: NotificationPushReceived,
        isAppInForeground: Boolean
    )
}

interface SlockRealtimeHostContext : SlockRealtimeAppScopeConnection, SlockRealtimeAppScopeSink {
    val enhancedNotifyBus: SlockEnhancedNotifyEventBus?
        get() = SlockEnhancedNotifyCenter

    val realtimeEngine: SlockRealtimeAppScopeEngine
        get() = SlockRealtimeAppScopeEngine()

    fun dispatchRealtime(block: suspend () -> Unit)
}

data class SlockRealtimeAppScopeConnectDecision(
    val shouldConnect: Boolean,
    val resumeSeq: Long,
    val sessionChanged: Boolean,
    // Diagnostic string, never absent: "" means "nothing to log". Kept non-null
    // like the sibling decision types because it crosses the KNOI bridge, where a
    // Kotlin null map value materializes as JS `undefined` (not null) and defeats
    // the ArkTS `!== null` guards.
    val logMessage: String
)

data class SlockRealtimeAppScopeRoomCommandDecision(
    val shouldEmit: Boolean,
    val eventName: String?,
    val payload: String?,
    val channelId: ChannelId?,
    val joinedChannelAction: String,
    val logMessage: String
)

data class SlockRealtimeAppScopeRawDecision(
    val shouldFanOut: Boolean,
    val serverId: ServerId?,
    val eventName: String,
    val rawPayload: String,
    val logMessage: String
)

data class SlockRealtimeAppScopeMappedDecision(
    val serverId: ServerId?,
    val eventName: String,
    val logMessage: String,
    val roomCommandDecision: SlockRealtimeAppScopeRoomCommandDecision?,
    val shouldPostBackgroundUnreadNotification: Boolean,
    val isAppInForeground: Boolean
)

/**
 * C/NAPI-friendly app-scope realtime state machine.
 *
 * This class intentionally does not depend on Android Context, Kuikly Pager,
 * ArkTS objects, or platform socket classes. Android can wrap it with the
 * higher-level coordinator below; OHOS can reach the primitive host methods
 * through the generated Kotlin/Native `libshared_api.h` surface.
 */
class SlockRealtimeAppScopeEngine {
    private val state = SlockRealtimeAppScopeState()
    private var appVisibility: SlockRealtimeAppVisibility = SlockRealtimeAppVisibility.Background

    val joinedChannelCount: Int
        get() = state.joinedChannelCount

    val lastSeq: Long
        get() = state.lastSeq

    val serverId: ServerId?
        get() = state.serverId

    val isAppInForeground: Boolean
        get() = appVisibility == SlockRealtimeAppVisibility.Foreground

    fun ownedSession(): SlockRealtimeOwnedSession? =
        state.ownedSession()

    fun onAppShown() {
        appVisibility = SlockRealtimeAppVisibility.Foreground
    }

    fun onAppHidden() {
        appVisibility = SlockRealtimeAppVisibility.Background
    }

    fun advanceAppliedSeqForHost(seq: Long): Long {
        state.advanceAppliedSeq(seq)
        return state.lastSeq
    }

    fun connectDecisionForHost(
        serverIdValue: String,
        accessToken: String,
        requestedLastSeq: Long,
        forceReconnect: Boolean,
        connectionStateName: String
    ): SlockRealtimeAppScopeConnectDecision =
        connectDecision(
            serverId = ServerId(serverIdValue),
            accessToken = accessToken,
            requestedLastSeq = requestedLastSeq,
            forceReconnect = forceReconnect,
            currentStateLabel = connectionStateName,
            isConnectionActive = connectionStateName in ActiveConnectionStateNames
        )

    fun connectDecision(
        serverId: ServerId,
        accessToken: String,
        requestedLastSeq: Long,
        forceReconnect: Boolean,
        currentState: RealtimeConnectionState
    ): SlockRealtimeAppScopeConnectDecision =
        connectDecision(
            serverId = serverId,
            accessToken = accessToken,
            requestedLastSeq = requestedLastSeq,
            forceReconnect = forceReconnect,
            currentStateLabel = currentState::class.simpleName ?: "unknown",
            isConnectionActive =
                currentState == RealtimeConnectionState.Connected ||
                    currentState == RealtimeConnectionState.RoomsJoined ||
                    currentState == RealtimeConnectionState.Connecting
        )

    private fun connectDecision(
        serverId: ServerId,
        accessToken: String,
        requestedLastSeq: Long,
        forceReconnect: Boolean,
        currentStateLabel: String,
        isConnectionActive: Boolean
    ): SlockRealtimeAppScopeConnectDecision {
        if (!forceReconnect && state.isSameSession(serverId, accessToken)) {
            if (isConnectionActive) {
                return SlockRealtimeAppScopeConnectDecision(
                    shouldConnect = false,
                    resumeSeq = state.lastSeq,
                    sessionChanged = false,
                    logMessage = "connect_skip_active state=$currentStateLabel serverId=${serverId.value}"
                )
            }
            val logMessage = "connect_same_session_reconnect state=$currentStateLabel serverId=${serverId.value}"
            val connectPlan =
                state.prepareConnect(
                    serverId = serverId,
                    accessToken = accessToken,
                    requestedLastSeq = requestedLastSeq
                )
            return SlockRealtimeAppScopeConnectDecision(
                shouldConnect = true,
                resumeSeq = connectPlan.resumeSeq,
                sessionChanged = connectPlan.sessionChanged,
                logMessage = logMessage
            )
        }
        val connectPlan =
            state.prepareConnect(
                serverId = serverId,
                accessToken = accessToken,
                requestedLastSeq = requestedLastSeq,
                // A transport reconnect must resume the same authority
                // session. Environment/account/auth-family replacement
                // creates a new runtime/session and resets through
                // sessionChanged instead.
                rebaseline = false
            )
        return SlockRealtimeAppScopeConnectDecision(
            shouldConnect = true,
            resumeSeq = connectPlan.resumeSeq,
            sessionChanged = connectPlan.sessionChanged,
            logMessage =
                "connect_start sessionChanged=${connectPlan.sessionChanged} " +
                    "resumeSeq=${connectPlan.resumeSeq} epoch=${connectPlan.epoch} " +
                    "rebaseline=${connectPlan.rebaseline} serverId=${serverId.value}"
        )
    }

    fun disconnectForSessionEnd(reason: String): Boolean {
        val cleanReason = reason.takeIf { it.isNotBlank() } ?: "shared_disconnect"
        if (!SlockRealtimeAppScopePolicy.isSessionEndDisconnectReason(cleanReason)) {
            return false
        }
        state.resetForSessionEnd()
        return true
    }

    fun joinChannelDecisionForHost(channelIdValue: String, reason: String): SlockRealtimeAppScopeRoomCommandDecision =
        joinChannelDecision(ChannelId(channelIdValue), reason)

    /**
     * Host-facing reset for hosts that scope an engine instance to room-command
     * bookkeeping only (iOS). Must be called wherever the host clears its own
     * session state, so stale joined-set entries cannot suppress joins on the
     * next session.
     */
    fun resetRoomStateForHost() {
        state.resetForSessionEnd()
    }

    fun roomCommandDecisionForHost(
        action: String,
        channelIdValue: String,
        reason: String
    ): SlockRealtimeAppScopeRoomCommandDecision =
        roomCommandDecision(action = action, channelId = ChannelId(channelIdValue), reason = reason)

    fun roomCommandDecision(
        action: String,
        channelId: ChannelId,
        reason: String = "notify"
    ): SlockRealtimeAppScopeRoomCommandDecision {
        if (channelId.value.isBlank()) {
            return SlockRealtimeAppScopeRoomCommandDecision(
                shouldEmit = false,
                eventName = null,
                payload = null,
                channelId = null,
                joinedChannelAction = SlockRealtimeRoomJoinedChannelAction.None,
                logMessage = "room_command_skip reason=missing_channel_id action=$action"
            )
        }
        return when (action) {
            SlockRealtimeNotifyContract.ActionJoinChannel -> joinChannelDecision(channelId, reason)
            SlockRealtimeNotifyContract.ActionRejoinChannel -> joinChannelDecision(channelId, reason, force = true)
            SlockRealtimeNotifyContract.ActionLeaveChannel -> leaveChannelDecision(channelId, reason)
            else ->
                SlockRealtimeAppScopeRoomCommandDecision(
                    shouldEmit = false,
                    eventName = null,
                    payload = null,
                    channelId = null,
                    joinedChannelAction = SlockRealtimeRoomJoinedChannelAction.None,
                    logMessage = "room_command_skip reason=unsupported_action action=$action"
                )
        }
    }

    fun joinChannelDecision(
        channelId: ChannelId,
        reason: String = "direct",
        force: Boolean = false
    ): SlockRealtimeAppScopeRoomCommandDecision {
        if (channelId.value.isBlank()) {
            return SlockRealtimeAppScopeRoomCommandDecision(
                shouldEmit = false,
                eventName = null,
                payload = null,
                channelId = null,
                joinedChannelAction = SlockRealtimeRoomJoinedChannelAction.None,
                logMessage = "room_join_skip reason=missing_channel_id"
            )
        }
        // Foreground reconcile: local joined-set may say "joined" while the
        // server-side socket room membership was lost across background or
        // reconnect. force re-emits join:channel (server join is idempotent
        // and re-verifies access) while keeping the joined set accurate.
        val newlyJoined = state.markChannelJoined(channelId)
        if (!newlyJoined && !force) {
            return SlockRealtimeAppScopeRoomCommandDecision(
                shouldEmit = false,
                eventName = null,
                payload = null,
                channelId = null,
                joinedChannelAction = SlockRealtimeRoomJoinedChannelAction.None,
                logMessage = "room_join_skip reason=already_joined channelId=${channelId.value}"
            )
        }
        return SlockRealtimeAppScopeRoomCommandDecision(
            shouldEmit = true,
            eventName = RealtimeSocketEvents.JoinChannel,
            payload = channelId.value,
            channelId = channelId,
            joinedChannelAction =
                if (force) {
                    SlockRealtimeRoomJoinedChannelAction.Rejoin
                } else {
                    SlockRealtimeRoomJoinedChannelAction.Join
                },
            logMessage = "room_join_emit reason=$reason force=$force channelId=${channelId.value}"
        )
    }

    fun leaveChannelDecision(channelId: ChannelId, reason: String = "direct"): SlockRealtimeAppScopeRoomCommandDecision {
        if (channelId.value.isBlank()) {
            return SlockRealtimeAppScopeRoomCommandDecision(
                shouldEmit = false,
                eventName = null,
                payload = null,
                channelId = null,
                joinedChannelAction = SlockRealtimeRoomJoinedChannelAction.None,
                logMessage = "room_leave_skip reason=missing_channel_id"
            )
        }
        if (!state.markChannelLeft(channelId)) {
            return SlockRealtimeAppScopeRoomCommandDecision(
                shouldEmit = false,
                eventName = null,
                payload = null,
                channelId = null,
                joinedChannelAction = SlockRealtimeRoomJoinedChannelAction.None,
                logMessage = "room_leave_skip reason=not_joined channelId=${channelId.value}"
            )
        }
        return SlockRealtimeAppScopeRoomCommandDecision(
            shouldEmit = true,
            eventName = RealtimeSocketEvents.LeaveChannel,
            payload = channelId.value,
            channelId = channelId,
            joinedChannelAction = SlockRealtimeRoomJoinedChannelAction.Leave,
            logMessage = "room_leave_emit reason=$reason channelId=${channelId.value}"
        )
    }

    fun onMappedEvent(event: RealtimeEvent): SlockRealtimeAppScopeMappedDecision {
        val eventName = SlockRealtimeAppScopePolicy.nativeRealtimeEventName(event)
        val currentServerId =
            state.serverId
                ?: return SlockRealtimeAppScopeMappedDecision(
                    serverId = null,
                    eventName = eventName,
                    logMessage = "native_event_skip reason=missing_server event=$eventName",
                    roomCommandDecision = null,
                    shouldPostBackgroundUnreadNotification = false,
                    isAppInForeground = isAppInForeground
                )
        state.updateFromEvent(event)
        val roomCommandDecision =
            if (event is DirectMessageCreated) {
                joinChannelDecision(event.channelId, reason = "direct_message_created")
            } else {
                null
            }
        return SlockRealtimeAppScopeMappedDecision(
            serverId = currentServerId,
            eventName = eventName,
            logMessage = "",
            roomCommandDecision = roomCommandDecision,
            // task #459: the tray posts only from the server-qualified stream.
            shouldPostBackgroundUnreadNotification = event is NotificationPushReceived,
            isAppInForeground = isAppInForeground
        )
    }

    fun onRawEvent(eventName: String, rawPayload: String): SlockRealtimeAppScopeRawDecision {
        val currentServerId =
            state.serverId
                ?: return SlockRealtimeAppScopeRawDecision(
                    shouldFanOut = false,
                    serverId = null,
                    eventName = eventName,
                    rawPayload = rawPayload,
                    logMessage = "native_raw_event_skip reason=missing_server eventName=$eventName"
                )
        return onRawEventForConnection(currentServerId.value, eventName, rawPayload)
    }

    fun onRawEventForConnection(
        connectionServerIdValue: String,
        eventName: String,
        rawPayload: String,
    ): SlockRealtimeAppScopeRawDecision {
        val gate = realtimePayloadServerGate(connectionServerIdValue, rawPayload)
        if (!gate.shouldAccept) {
            return SlockRealtimeAppScopeRawDecision(
                shouldFanOut = false,
                serverId = null,
                eventName = eventName,
                rawPayload = rawPayload,
                logMessage =
                    "native_raw_event_skip reason=payload_server_${gate.reason} " +
                        "connectionServerId=${connectionServerIdValue.trim()} " +
                        "payloadServerId=${gate.payloadServerId} eventName=$eventName",
            )
        }
        return SlockRealtimeAppScopeRawDecision(
            shouldFanOut = true,
            serverId = ServerId(connectionServerIdValue.trim()),
            eventName = eventName,
            rawPayload = rawPayload,
            logMessage = "",
        )
    }

    companion object {
        const val StateConnected = "Connected"
        const val StateRoomsJoined = "RoomsJoined"
        const val StateConnecting = "Connecting"
        const val StateDisconnected = "Disconnected"
        const val StateFailed = "Failed"

        private val ActiveConnectionStateNames = setOf(StateConnected, StateRoomsJoined, StateConnecting)
    }
}

class SlockRealtimeAppScopeCoordinator(
    private val connection: SlockRealtimeAppScopeConnection,
    private val sink: SlockRealtimeAppScopeSink,
    private val enhancedNotifyBus: SlockEnhancedNotifyEventBus? = SlockEnhancedNotifyCenter,
    private val engine: SlockRealtimeAppScopeEngine = SlockRealtimeAppScopeEngine()
) {
    private var enhancedNotifySubscription: RealtimeSubscription? = null

    init {
        installEnhancedNotifyBus()
    }

    val joinedChannelCount: Int
        get() = engine.joinedChannelCount

    val lastSeq: Long
        get() = engine.lastSeq

    val serverId: ServerId?
        get() = engine.serverId

    fun advanceAppliedSeqForHost(seq: Long): Long =
        engine.advanceAppliedSeqForHost(seq)

    val isAppInForeground: Boolean
        get() = engine.isAppInForeground

    fun ownedSession(): SlockRealtimeOwnedSession? =
        engine.ownedSession()

    fun onAppShown() {
        engine.onAppShown()
    }

    fun onAppHidden() {
        engine.onAppHidden()
    }

    private fun installEnhancedNotifyBus() {
        if (enhancedNotifySubscription != null) return
        enhancedNotifySubscription =
            enhancedNotifyBus?.observeNotifyEvents { event ->
                onEnhancedNotify(event)
            }
    }

    suspend fun connect(
        serverId: ServerId,
        accessToken: String,
        requestedLastSeq: Long,
        forceReconnect: Boolean,
        witness: RealtimeConnectionWitnessToken,
    ): DataResult<Unit> {
        val decision =
            engine.connectDecision(
                serverId = serverId,
                accessToken = accessToken,
                requestedLastSeq = requestedLastSeq,
                forceReconnect = forceReconnect,
                currentState = connection.state
            )
        if (decision.logMessage.isNotEmpty()) sink.log(decision.logMessage)
        if (!decision.shouldConnect) {
            return DataResult.Content(Unit)
        }
        val result =
            connection.connect(
                serverId = serverId,
                accessToken = accessToken,
                lastSeq = decision.resumeSeq,
                witness = witness,
            )
        sink.log(
            "connect_result serverId=${serverId.value} result=${result::class.simpleName} " +
                "lastSeq=${decision.resumeSeq} session_changed=${decision.sessionChanged}"
        )
        return result
    }

    fun disconnectForSessionEnd(reason: String): Boolean {
        val cleanReason = reason.takeIf { it.isNotBlank() } ?: "shared_disconnect"
        if (!engine.disconnectForSessionEnd(cleanReason)) {
            sink.log("disconnect_ignored reason=$cleanReason")
            return false
        }
        connection.disconnect()
        sink.log("disconnect_requested reason=$cleanReason visibility=${if (isAppInForeground) "Foreground" else "Background"}")
        return true
    }

    fun onRoomCommand(
        action: String,
        channelId: ChannelId,
        reason: String = "notify"
    ): Boolean {
        val decision = engine.roomCommandDecision(action = action, channelId = channelId, reason = reason)
        sink.log(decision.logMessage)
        val eventName = decision.eventName ?: return false
        val payload = decision.payload ?: return false
        if (!decision.shouldEmit) return false
        connection.emitRoomCommand(
            eventName = eventName,
            payload = payload,
            force = decision.joinedChannelAction == SlockRealtimeRoomJoinedChannelAction.Rejoin
        )
        return true
    }

    fun onRoomCommand(
        action: String,
        channelIdValue: String?,
        reason: String = "notify"
    ): Boolean {
        val channelId = channelIdValue?.takeIf { it.isNotBlank() }?.let(::ChannelId)
        if (channelId == null) {
            sink.log("room_command_skip reason=missing_channel_id action=$action")
            return false
        }
        return onRoomCommand(action = action, channelId = channelId, reason = reason)
    }

    private fun onEnhancedNotify(event: SlockEnhancedNotifyEvent): Boolean {
        if (event.eventName != SlockRealtimeNotifyContract.RoomsEventName) return false
        val targetServerId =
            event.eventData.optString(SlockRealtimeNotifyContract.KeyServerId)
                .trim()
                .takeIf { it.isNotBlank() }
        val ownedServerId = engine.serverId?.value
        if (targetServerId == null) {
            sink.log("room_command_skip reason=missing_target_server ownedServerId=${ownedServerId.orEmpty()}")
            return false
        }
        if (targetServerId != ownedServerId) {
            sink.log("room_command_skip reason=server_mismatch targetServerId=$targetServerId ownedServerId=${ownedServerId.orEmpty()}")
            return false
        }
        return onRoomCommand(
            action = event.eventData.optString(SlockRealtimeNotifyContract.KeyAction),
            channelIdValue = event.eventData.optString(SlockRealtimeNotifyContract.KeyChannelId),
            reason = event.source
        )
    }

    suspend fun onMappedEvent(event: RealtimeEvent) {
        val decision = engine.onMappedEvent(event)
        if (decision.logMessage.isNotEmpty()) sink.log(decision.logMessage)
        val roomCommandDecision = decision.roomCommandDecision
        if (roomCommandDecision != null) {
            sink.log(roomCommandDecision.logMessage)
            val eventName = roomCommandDecision.eventName
            val payload = roomCommandDecision.payload
            if (roomCommandDecision.shouldEmit && eventName != null && payload != null) {
                connection.emitRoomCommand(
                    eventName = eventName,
                    payload = payload,
                    force = roomCommandDecision.joinedChannelAction == SlockRealtimeRoomJoinedChannelAction.Rejoin
                )
            }
        }
        val currentServerId = decision.serverId ?: return
        // task #459: the tray posts from the server-qualified stream only —
        // message:new is a data-sync trigger and never reaches the poster.
        if (decision.shouldPostBackgroundUnreadNotification && event is NotificationPushReceived) {
            sink.maybePostBackgroundUnreadNotification(
                serverId = currentServerId,
                event = event,
                isAppInForeground = decision.isAppInForeground
            )
        }
    }

    fun onRawEvent(
        context: RealtimeSocketConnectionContext,
        eventName: String,
        rawPayload: String,
        transportEpoch: Long? = null,
    ) {
        val decision = engine.onRawEventForConnection(context.serverId.value, eventName, rawPayload)
        if (decision.shouldFanOut) {
            sink.fanOutRawRealtimeEvent(
                serverId = context.serverId,
                witness = context.witness,
                eventName = decision.eventName,
                rawPayload = decision.rawPayload,
                transportEpoch = transportEpoch,
            )
        }
        if (decision.logMessage.isNotEmpty()) sink.log(decision.logMessage)
    }
}

class SlockRealtimeAppScopeRuntime(
    private val coordinator: SlockRealtimeAppScopeCoordinator,
    connection: SlockRealtimeAppScopeConnection,
    dispatch: (suspend () -> Unit) -> Unit
) {
    private val eventSubscription: RealtimeSubscription =
        connection.observeEvents { event ->
            dispatch {
                coordinator.onMappedEvent(event)
            }
        }
    private val rawEventSubscription: RealtimeSubscription =
        connection.observeRawEvents { context, eventName, rawPayload, transportEpoch ->
            dispatch {
                coordinator.onRawEvent(context, eventName, rawPayload, transportEpoch)
            }
        }

    val joinedChannelCount: Int
        get() = coordinator.joinedChannelCount

    val lastSeq: Long
        get() = coordinator.lastSeq

    val serverId: ServerId?
        get() = coordinator.serverId

    fun advanceAppliedSeqForHost(seq: Long): Long =
        coordinator.advanceAppliedSeqForHost(seq)

    val isAppInForeground: Boolean
        get() = coordinator.isAppInForeground

    fun ownedSession(): SlockRealtimeOwnedSession? =
        coordinator.ownedSession()

    fun onAppShown() {
        coordinator.onAppShown()
    }

    fun onAppHidden() {
        coordinator.onAppHidden()
    }

    suspend fun connect(
        serverId: ServerId,
        accessToken: String,
        requestedLastSeq: Long,
        forceReconnect: Boolean,
        witness: RealtimeConnectionWitnessToken,
    ): DataResult<Unit> =
        coordinator.connect(
            serverId = serverId,
            accessToken = accessToken,
            requestedLastSeq = requestedLastSeq,
            forceReconnect = forceReconnect,
            witness = witness,
        )

    fun disconnectForSessionEnd(reason: String): Boolean =
        coordinator.disconnectForSessionEnd(reason)

    fun dispose() {
        eventSubscription.close()
        rawEventSubscription.close()
    }
}

fun bootstrapRealtime(
    connection: SlockRealtimeAppScopeConnection,
    sink: SlockRealtimeAppScopeSink,
    enhancedNotifyBus: SlockEnhancedNotifyEventBus? = SlockEnhancedNotifyCenter,
    engine: SlockRealtimeAppScopeEngine = SlockRealtimeAppScopeEngine(),
    dispatch: (suspend () -> Unit) -> Unit
): SlockRealtimeAppScopeRuntime {
    val coordinator =
        SlockRealtimeAppScopeCoordinator(
            connection = connection,
            sink = sink,
            enhancedNotifyBus = enhancedNotifyBus,
            engine = engine
        )
    return SlockRealtimeAppScopeRuntime(
        coordinator = coordinator,
        connection = connection,
        dispatch = dispatch
    )
}

fun bootstrapRealtime(hostContext: SlockRealtimeHostContext): SlockRealtimeAppScopeRuntime =
    bootstrapRealtime(
        connection = hostContext,
        sink = hostContext,
        enhancedNotifyBus = hostContext.enhancedNotifyBus,
        engine = hostContext.realtimeEngine,
        dispatch = hostContext::dispatchRealtime
    )

enum class SlockRealtimeAppVisibility {
    Foreground,
    Background
}
