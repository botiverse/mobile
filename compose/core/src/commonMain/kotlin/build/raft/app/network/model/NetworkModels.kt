package build.raft.app.network.model

import kotlinx.serialization.Serializable

@Serializable
enum class NetworkIssueKind {
    Timeout,
    Dns,
    Tls,
    ConnectionReset,
    ServerUnavailable,
    Unauthorized,
    Forbidden,
    RateLimited,
    BusinessError,
    Cancelled,
    Unknown
}

@Serializable
data class NetworkIssue(
    val kind: NetworkIssueKind,
    val retryable: Boolean,
    val userVisible: Boolean,
    val diagnosticCode: String,
    val httpStatusCode: Int? = null,
    val serverCode: String? = null,
    val retryAfterMillis: Long? = null,
    val message: String? = null
)

@Serializable
enum class RealtimeConnectionPhase {
    Disconnected,
    Connecting,
    Connected,
    Reconnecting,
    Failed
}

@Serializable
data class RealtimeConnectionState(
    val phase: RealtimeConnectionPhase = RealtimeConnectionPhase.Disconnected,
    val reconnectAttempt: Int = 0,
    val lastSeq: Long? = null,
    val serverMaxSeq: Long? = null,
    val lastHeartbeatAtMillis: Long? = null,
    val lastConnectedAtMillis: Long? = null,
    val lastDisconnectedAtMillis: Long? = null,
    val lastDisconnectReason: String? = null,
    val nextRetryDelayMillis: Long? = null,
    val issue: NetworkIssue? = null
) {
    val connected: Boolean
        get() = phase == RealtimeConnectionPhase.Connected

    val reconnecting: Boolean
        get() = phase == RealtimeConnectionPhase.Reconnecting
}
