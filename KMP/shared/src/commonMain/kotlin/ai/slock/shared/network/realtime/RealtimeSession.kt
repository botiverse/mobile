package ai.slock.shared.network.realtime

import ai.slock.shared.model.RealtimeEvent
import ai.slock.shared.model.ServerId
import ai.slock.shared.network.model.RealtimeConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * Manages a realtime session with connection state machine and retry logic
 *
 * This class bridges the platform-specific transport with KMP state management:
 * - Observes transport state and maps to connection events
 * - Applies retry policy for connection failures
 * - Provides unified connection state and event streams
 */
class RealtimeSession(
    private val transport: RealtimeTransport,
    private val reducer: RealtimeConnectionReducer
) {
    private val _connectionState = MutableStateFlow<RealtimeConnectionState>(
        RealtimeConnectionState()
    )

    /**
     * Current connection state (managed by KMP state machine)
     */
    val connectionState: StateFlow<RealtimeConnectionState> = _connectionState

    /**
     * Incoming realtime events
     */
    val events: Flow<RealtimeEvent> = transport.events.map { it.event }

    /**
     * Pending connection intents (for retry scheduling)
     */
    private val _pendingIntents = MutableStateFlow<List<RealtimeConnectionIntent>>(emptyList())
    val pendingIntents: StateFlow<List<RealtimeConnectionIntent>> = _pendingIntents

    /**
     * Connect to the realtime server
     *
     * @param auth Authentication credentials
     * @param nowMillis Current timestamp
     */
    suspend fun connect(auth: RealtimeAuth, nowMillis: Long) {
        // Emit connect event to state machine
        val event = RealtimeConnectionEvent.ConnectRequested(nowMillis)
        val result = reducer.reduce(_connectionState.value, event)
        _connectionState.value = result.state
        _pendingIntents.value = result.intents

        try {
            // Delegate to transport
            transport.connect(auth)
        } catch (e: RealtimeTransportException) {
            // Handle connection failure
            val failureEvent = RealtimeConnectionEvent.ConnectionFailed(
                nowMillis = nowMillis,
                issue = ai.slock.shared.network.model.NetworkIssue(
                    kind = ai.slock.shared.network.model.NetworkIssueKind.Unknown,
                    retryable = true,
                    userVisible = false,
                    diagnosticCode = e.code ?: "transport_error",
                    message = e.message
                )
            )
            val failedResult = reducer.reduce(_connectionState.value, failureEvent)
            _connectionState.value = failedResult.state
            _pendingIntents.value = failedResult.intents

            throw e
        }
    }

    /**
     * Handle successful connection
     */
    suspend fun onConnected(nowMillis: Long, serverSeq: Long?) {
        val event = RealtimeConnectionEvent.Connected(nowMillis, serverSeq)
        val result = reducer.reduce(_connectionState.value, event)
        _connectionState.value = result.state
        _pendingIntents.value = result.intents
    }

    /**
     * Handle disconnection
     */
    suspend fun onDisconnected(nowMillis: Long, reason: String) {
        val event = RealtimeConnectionEvent.Disconnected(nowMillis, reason)
        val result = reducer.reduce(_connectionState.value, event)
        _connectionState.value = result.state
        _pendingIntents.value = result.intents
    }

    /**
     * Disconnect from the realtime server
     */
    suspend fun disconnect() {
        transport.disconnect()
    }

    /**
     * Emit an event to the server
     */
    suspend fun emit(event: String, data: Map<String, Any?>) {
        transport.emit(event, data)
    }
}

/**
 * Exception thrown by realtime session
 */
class RealtimeSessionException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
