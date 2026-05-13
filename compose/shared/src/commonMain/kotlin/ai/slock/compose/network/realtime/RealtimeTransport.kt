package ai.slock.compose.network.realtime

import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.ServerId
import kotlinx.coroutines.flow.Flow

/**
 * Platform-agnostic interface for realtime transport (Socket.IO, WebSocket, etc.)
 *
 * Implementations should handle:
 * - Platform-specific socket client lifecycle
 * - Network connectivity monitoring
 * - Background/foreground transitions
 * - TLS/proxy configuration
 *
 * The KMP layer handles:
 * - Connection state machine
 * - Retry/backoff logic
 * - Gap detection
 * - Diagnostics
 */
interface RealtimeTransport {
    /**
     * Connection state observable
     */
    val connectionState: Flow<RealtimeTransportState>

    /**
     * Incoming events from the server
     */
    val events: Flow<RealtimeEventEnvelope>

    /**
     * Connect to the realtime server
     *
     * @param auth Authentication credentials
     * @throws RealtimeTransportException on connection failure
     */
    suspend fun connect(auth: RealtimeAuth)

    /**
     * Disconnect from the realtime server
     */
    suspend fun disconnect()

    /**
     * Emit an event to the server
     *
     * @param event Event name (e.g., "sync:resume", "channel:join")
     * @param data Event payload
     */
    suspend fun emit(event: String, data: Map<String, Any?>)
}

/**
 * Authentication credentials for realtime connection
 */
data class RealtimeAuth(
    val token: String,
    val serverId: ServerId
)

/**
 * Transport connection state
 */
sealed class RealtimeTransportState {
    object Disconnected : RealtimeTransportState()
    object Connecting : RealtimeTransportState()
    object Connected : RealtimeTransportState()
    data class Error(val reason: String, val code: String?) : RealtimeTransportState()
}

/**
 * Envelope for incoming realtime events
 */
data class RealtimeEventEnvelope(
    val event: RealtimeEvent
)

/**
 * Exception thrown by transport layer
 */
class RealtimeTransportException(
    message: String,
    val code: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)
