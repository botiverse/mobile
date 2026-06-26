package ai.slock.compose.data.realtime

import ai.slock.compose.data.DataResult
import ai.slock.compose.data.RealtimeSubscription
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.DirectMessageCreated
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.ServerId

interface SlockRealtimeAppScopeConnection {
    val state: RealtimeConnectionState

    suspend fun connect(serverId: ServerId, accessToken: String, lastSeq: Long): DataResult<Unit>
    fun disconnect()
    fun emitJoinChannel(channelId: ChannelId)
    fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription
    fun observeRawEvents(listener: (eventName: String, rawPayload: String) -> Unit): RealtimeSubscription
}

data class SlockRealtimeRoomCommand(
    val action: String,
    val channelIdValue: String?,
    val reason: String = "notify"
)

interface SlockRealtimeAppScopeRoomCommandBus {
    fun observeRoomCommands(listener: (SlockRealtimeRoomCommand) -> Unit): RealtimeSubscription
}

object SlockRealtimeRoomCommandHub : SlockRealtimeAppScopeRoomCommandBus {
    private val listeners = mutableListOf<(SlockRealtimeRoomCommand) -> Unit>()

    override fun observeRoomCommands(listener: (SlockRealtimeRoomCommand) -> Unit): RealtimeSubscription {
        listeners += listener
        return RealtimeSubscription {
            listeners -= listener
        }
    }

    fun publish(command: SlockRealtimeRoomCommand) {
        listeners.toList().forEach { listener ->
            listener(command)
        }
    }
}

interface SlockRealtimeAppScopeSink {
    fun log(message: String)
    fun fanOutRawRealtimeEvent(serverId: ServerId, eventName: String, rawPayload: String)
    suspend fun maybePostBackgroundUnreadNotification(
        serverId: ServerId,
        event: MessageNew,
        isAppInForeground: Boolean
    )
}

data class SlockRealtimeAppScopeConnectDecision(
    val shouldConnect: Boolean,
    val resumeSeq: Long,
    val sessionChanged: Boolean,
    val logMessage: String?
)

data class SlockRealtimeAppScopeJoinDecision(
    val shouldJoin: Boolean,
    val channelId: ChannelId?,
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
    val joinDecision: SlockRealtimeAppScopeJoinDecision?,
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
                    logMessage = null
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
                requestedLastSeq = requestedLastSeq
            )
        return SlockRealtimeAppScopeConnectDecision(
            shouldConnect = true,
            resumeSeq = connectPlan.resumeSeq,
            sessionChanged = connectPlan.sessionChanged,
            logMessage = null
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

    fun joinChannelDecisionForHost(channelIdValue: String, reason: String): SlockRealtimeAppScopeJoinDecision =
        joinChannelDecision(ChannelId(channelIdValue), reason)

    fun roomCommandDecisionForHost(
        action: String,
        channelIdValue: String,
        reason: String
    ): SlockRealtimeAppScopeJoinDecision =
        roomCommandDecision(action = action, channelId = ChannelId(channelIdValue), reason = reason)

    fun roomCommandDecision(
        action: String,
        channelId: ChannelId,
        reason: String = "notify"
    ): SlockRealtimeAppScopeJoinDecision {
        if (channelId.value.isBlank()) {
            return SlockRealtimeAppScopeJoinDecision(
                shouldJoin = false,
                channelId = null,
                logMessage = "room_command_skip reason=missing_channel_id action=$action"
            )
        }
        if (action != SlockRealtimeNotifyContract.ActionJoinChannel) {
            return SlockRealtimeAppScopeJoinDecision(
                shouldJoin = false,
                channelId = null,
                logMessage = "room_command_skip reason=unsupported_action action=$action"
            )
        }
        return joinChannelDecision(channelId, reason)
    }

    fun joinChannelDecision(channelId: ChannelId, reason: String = "direct"): SlockRealtimeAppScopeJoinDecision {
        if (channelId.value.isBlank()) {
            return SlockRealtimeAppScopeJoinDecision(
                shouldJoin = false,
                channelId = null,
                logMessage = "room_join_skip reason=missing_channel_id"
            )
        }
        if (!state.markChannelJoined(channelId)) {
            return SlockRealtimeAppScopeJoinDecision(
                shouldJoin = false,
                channelId = null,
                logMessage = "room_join_skip reason=already_joined channelId=${channelId.value}"
            )
        }
        return SlockRealtimeAppScopeJoinDecision(
            shouldJoin = true,
            channelId = channelId,
            logMessage = "room_join_emit reason=$reason channelId=${channelId.value}"
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
                    joinDecision = null,
                    shouldPostBackgroundUnreadNotification = false,
                    isAppInForeground = isAppInForeground
                )
        val receivedLog =
            "native_event_received event=$eventName serverId=${currentServerId.value} " +
                "joined_rooms=${state.joinedChannelCount}"
        state.updateFromEvent(event)
        val joinDecision =
            if (event is DirectMessageCreated) {
                joinChannelDecision(event.channelId, reason = "direct_message_created")
            } else {
                null
            }
        return SlockRealtimeAppScopeMappedDecision(
            serverId = currentServerId,
            eventName = eventName,
            logMessage = receivedLog,
            joinDecision = joinDecision,
            shouldPostBackgroundUnreadNotification = event is MessageNew,
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
        if (eventName == RealtimeSocketEvents.Heartbeat) {
            return SlockRealtimeAppScopeRawDecision(
                shouldFanOut = false,
                serverId = currentServerId,
                eventName = eventName,
                rawPayload = rawPayload,
                logMessage =
                    "raw_event_fanout_skip eventName=$eventName serverId=${currentServerId.value} " +
                        "reason=native_heartbeat"
            )
        }
        return SlockRealtimeAppScopeRawDecision(
            shouldFanOut = true,
            serverId = currentServerId,
            eventName = eventName,
            rawPayload = rawPayload,
            logMessage = "raw_event_notify eventName=$eventName serverId=${currentServerId.value}"
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
    private val roomCommandBus: SlockRealtimeAppScopeRoomCommandBus? = SlockRealtimeRoomCommandHub,
    private val engine: SlockRealtimeAppScopeEngine = SlockRealtimeAppScopeEngine()
) {
    private var roomCommandSubscription: RealtimeSubscription? = null

    init {
        installRoomCommandBus()
    }

    val joinedChannelCount: Int
        get() = engine.joinedChannelCount

    val lastSeq: Long
        get() = engine.lastSeq

    val serverId: ServerId?
        get() = engine.serverId

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

    private fun installRoomCommandBus() {
        if (roomCommandSubscription != null) return
        roomCommandSubscription =
            roomCommandBus?.observeRoomCommands { command ->
                onRoomCommand(
                    action = command.action,
                    channelIdValue = command.channelIdValue,
                    reason = command.reason
                )
            }
    }

    suspend fun connect(
        serverId: ServerId,
        accessToken: String,
        requestedLastSeq: Long,
        forceReconnect: Boolean
    ): DataResult<Unit> {
        val decision =
            engine.connectDecision(
                serverId = serverId,
                accessToken = accessToken,
                requestedLastSeq = requestedLastSeq,
                forceReconnect = forceReconnect,
                currentState = connection.state
            )
        decision.logMessage?.let(sink::log)
        if (!decision.shouldConnect) {
            return DataResult.Content(Unit)
        }
        val result =
            connection.connect(
                serverId = serverId,
                accessToken = accessToken,
                lastSeq = decision.resumeSeq
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
        val joinedChannel = decision.channelId ?: return false
        connection.emitJoinChannel(joinedChannel)
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

    suspend fun onMappedEvent(event: RealtimeEvent) {
        val decision = engine.onMappedEvent(event)
        sink.log(decision.logMessage)
        val joinDecision = decision.joinDecision
        if (joinDecision != null) {
            sink.log(joinDecision.logMessage)
            joinDecision.channelId?.let(connection::emitJoinChannel)
        }
        val currentServerId = decision.serverId ?: return
        if (decision.shouldPostBackgroundUnreadNotification && event is MessageNew) {
            sink.maybePostBackgroundUnreadNotification(
                serverId = currentServerId,
                event = event,
                isAppInForeground = decision.isAppInForeground
            )
        }
    }

    fun onRawEvent(eventName: String, rawPayload: String) {
        val decision = engine.onRawEvent(eventName, rawPayload)
        if (decision.shouldFanOut) {
            val currentServerId = decision.serverId
            if (currentServerId != null) {
                sink.fanOutRawRealtimeEvent(
                    serverId = currentServerId,
                    eventName = decision.eventName,
                    rawPayload = decision.rawPayload
                )
            }
        }
        sink.log(decision.logMessage)
    }
}

enum class SlockRealtimeAppVisibility {
    Foreground,
    Background
}
