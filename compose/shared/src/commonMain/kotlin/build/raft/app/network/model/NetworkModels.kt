package build.raft.app.network.model

import kotlinx.serialization.Serializable

@Serializable
enum class NetworkStatus {
    Online,
    Offline,
    Degraded,
    Unknown
}

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

object NetworkIssueClassifier {
    fun fromHttpStatus(
        statusCode: Int,
        serverCode: String? = null,
        retryAfterMillis: Long? = null,
        message: String? = null
    ): NetworkIssue =
        when (statusCode) {
            401 ->
                NetworkIssue(
                    kind = NetworkIssueKind.Unauthorized,
                    retryable = false,
                    userVisible = true,
                    diagnosticCode = serverCode ?: "http_401",
                    httpStatusCode = statusCode,
                    serverCode = serverCode,
                    message = message
                )
            403 ->
                NetworkIssue(
                    kind = NetworkIssueKind.Forbidden,
                    retryable = false,
                    userVisible = true,
                    diagnosticCode = serverCode ?: "http_403",
                    httpStatusCode = statusCode,
                    serverCode = serverCode,
                    message = message
                )
            408 ->
                retryableHttp(
                    statusCode = statusCode,
                    kind = NetworkIssueKind.Timeout,
                    serverCode = serverCode,
                    retryAfterMillis = retryAfterMillis,
                    message = message
                )
            429 ->
                retryableHttp(
                    statusCode = statusCode,
                    kind = NetworkIssueKind.RateLimited,
                    serverCode = serverCode,
                    retryAfterMillis = retryAfterMillis,
                    message = message
                )
            in 500..599 ->
                retryableHttp(
                    statusCode = statusCode,
                    kind = NetworkIssueKind.ServerUnavailable,
                    serverCode = serverCode,
                    retryAfterMillis = retryAfterMillis,
                    message = message
                )
            in 400..499 ->
                NetworkIssue(
                    kind = NetworkIssueKind.BusinessError,
                    retryable = false,
                    userVisible = true,
                    diagnosticCode = serverCode ?: "http_$statusCode",
                    httpStatusCode = statusCode,
                    serverCode = serverCode,
                    message = message
                )
            else ->
                NetworkIssue(
                    kind = NetworkIssueKind.Unknown,
                    retryable = false,
                    userVisible = true,
                    diagnosticCode = serverCode ?: "http_$statusCode",
                    httpStatusCode = statusCode,
                    serverCode = serverCode,
                    message = message
                )
        }

    fun fromTransport(
        kind: NetworkIssueKind,
        diagnosticCode: String,
        message: String? = null
    ): NetworkIssue {
        val retryable =
            kind == NetworkIssueKind.Timeout ||
                kind == NetworkIssueKind.Dns ||
                kind == NetworkIssueKind.Tls ||
                kind == NetworkIssueKind.ConnectionReset ||
                kind == NetworkIssueKind.ServerUnavailable
        return NetworkIssue(
            kind = kind,
            retryable = retryable,
            userVisible = true,
            diagnosticCode = diagnosticCode,
            message = message
        )
    }

    private fun retryableHttp(
        statusCode: Int,
        kind: NetworkIssueKind,
        serverCode: String?,
        retryAfterMillis: Long?,
        message: String?
    ): NetworkIssue =
        NetworkIssue(
            kind = kind,
            retryable = true,
            userVisible = true,
            diagnosticCode = serverCode ?: "http_$statusCode",
            httpStatusCode = statusCode,
            serverCode = serverCode,
            retryAfterMillis = retryAfterMillis,
            message = message
        )
}

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

@Serializable
sealed class BackfillScope {
    @Serializable
    data class Home(val serverId: String) : BackfillScope()

    @Serializable
    data class Thread(val threadId: String) : BackfillScope()

    @Serializable
    data class Inbox(val serverId: String) : BackfillScope()

    @Serializable
    data class Tasks(val serverId: String? = null, val channelId: String? = null) : BackfillScope()
}

@Serializable
enum class OutboxOperationType {
    SendMessage,
    UpdateTask,
    CreateTask,
    SaveMessage,
    Custom
}

@Serializable
enum class OutboxOperationStatus {
    Pending,
    Sending,
    Failed,
    Synced,
    Cancelled
}

@Serializable
data class OutboxOperation(
    val id: String,
    val type: OutboxOperationType,
    val idempotencyKey: String,
    val scope: BackfillScope,
    val createdAtMillis: Long,
    val status: OutboxOperationStatus = OutboxOperationStatus.Pending,
    val attempt: Int = 0,
    val payloadSummary: String? = null,
    val lastIssue: NetworkIssue? = null
) {
    fun markSending(): OutboxOperation =
        copy(status = OutboxOperationStatus.Sending, attempt = attempt + 1)

    fun markFailed(issue: NetworkIssue): OutboxOperation =
        copy(status = OutboxOperationStatus.Failed, lastIssue = issue)

    fun markSynced(): OutboxOperation =
        copy(status = OutboxOperationStatus.Synced, lastIssue = null)
}

@Serializable
data class ConnectionHealth(
    val networkStatus: NetworkStatus = NetworkStatus.Unknown,
    val apiReachable: Boolean? = null,
    val realtime: RealtimeConnectionState = RealtimeConnectionState(),
    val lastApiSuccessAtMillis: Long? = null,
    val lastApiFailureAtMillis: Long? = null,
    val lastApiIssue: NetworkIssue? = null
)

@Serializable
data class NetworkDiagnosticsSnapshot(
    val capturedAtMillis: Long,
    val baseUrl: String? = null,
    val socketUrl: String? = null,
    val serverId: String? = null,
    val buildLabel: String? = null,
    val health: ConnectionHealth = ConnectionHealth(),
    val pendingOutboxCount: Int = 0,
    val failedOutboxCount: Int = 0,
    val lastSyncResult: String? = null
) {
    fun toRedactedText(): String =
        buildString {
            appendLine("network_status=${health.networkStatus}")
            appendLine("api_reachable=${health.apiReachable}")
            appendLine("realtime_phase=${health.realtime.phase}")
            appendLine("realtime_attempt=${health.realtime.reconnectAttempt}")
            appendLine("last_seq=${health.realtime.lastSeq ?: ""}")
            appendLine("server_max_seq=${health.realtime.serverMaxSeq ?: ""}")
            appendLine("last_heartbeat_at=${health.realtime.lastHeartbeatAtMillis ?: ""}")
            appendLine("last_disconnect_reason=${health.realtime.lastDisconnectReason.orEmpty()}")
            appendLine("last_api_issue=${health.lastApiIssue?.diagnosticCode.orEmpty()}")
            appendLine("pending_outbox_count=$pendingOutboxCount")
            appendLine("failed_outbox_count=$failedOutboxCount")
            appendLine("last_sync_result=${lastSyncResult.orEmpty()}")
            appendLine("base_url=${baseUrl.redactUrl()}")
            appendLine("socket_url=${socketUrl.redactUrl()}")
            appendLine("server_id=${serverId.orEmpty()}")
            appendLine("build=${buildLabel.orEmpty()}")
        }

    private fun String?.redactUrl(): String =
        this?.substringBefore('?')?.replace(Regex("(?i)(token|key|secret)=[^&]+"), "$1=<redacted>")
            .orEmpty()
}
