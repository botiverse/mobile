package build.raft.app.core.data.diagnostics

import build.raft.app.core.data.auth.AndroidAuthApiFactory
import build.raft.app.data.realtime.RealtimeClientDiagnostics
import build.raft.app.core.data.realtime.SocketIoRealtimeClient
import build.raft.app.data.DataResult
import build.raft.app.model.Heartbeat
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import build.raft.app.model.SyncResumeResponse
import build.raft.app.network.model.ConnectionHealth
import build.raft.app.network.model.NetworkDiagnosticsSnapshot
import build.raft.app.network.model.NetworkIssue
import build.raft.app.network.model.NetworkIssueClassifier
import build.raft.app.network.model.NetworkIssueKind
import build.raft.app.network.model.NetworkStatus
import build.raft.app.network.model.RealtimeConnectionPhase
import build.raft.app.network.model.RealtimeConnectionState
import build.raft.app.network.realtime.RealtimeConnectionEvent
import build.raft.app.network.realtime.RealtimeConnectionIntent
import build.raft.app.network.realtime.RealtimeConnectionReducer
import java.util.concurrent.atomic.AtomicReference

class NetworkDiagnosticsBridge(
        private val baseUrl: String = AndroidAuthApiFactory.BASE_URL,
        private val socketUrl: String = SocketIoRealtimeClient.DEFAULT_SOCKET_URL,
        private val buildLabel: String? = null,
        private val nowMillis: () -> Long = System::currentTimeMillis,
        private val logger: (String) -> Unit = { message ->
            RaftAppLog.d("SlockNetwork", message)
        }
) : RealtimeClientDiagnostics {
    private val reducer = RealtimeConnectionReducer()
    private val stateRef = AtomicReference(NetworkDiagnosticsState())
    private val pendingIntentsRef = AtomicReference<List<RealtimeConnectionIntent>>(emptyList())

    fun snapshot(): NetworkDiagnosticsSnapshot = stateRef.get().toSnapshot(nowMillis())

    fun drainPendingIntents(): List<RealtimeConnectionIntent> =
            pendingIntentsRef.getAndSet(emptyList())

    fun onAppForegroundChanged(inForeground: Boolean, serverId: ServerId?) {
        if (inForeground) {
            update { it.copy(networkStatus = NetworkStatus.Online, serverId = serverId?.value) }
        } else {
            onIntentionalDisconnect(reason = "background", serverId = serverId)
        }
    }

    override fun onConnectRequested(serverId: ServerId, lastSeq: Long) {
        applyRealtimeEvent(
                event = RealtimeConnectionEvent.ConnectRequested(nowMillis()),
                serverId = serverId,
                lastSeq = lastSeq
        )
    }

    fun onRealtimeConnectResult(serverId: ServerId, result: DataResult<Unit>) {
        when (result) {
            is DataResult.Content ->
                    update { current ->
                        current.copy(
                                networkStatus = NetworkStatus.Online,
                                apiReachable = true,
                                serverId = serverId.value,
                                lastApiSuccessAtMillis = nowMillis(),
                                lastApiIssue = null
                        )
                    }
            is DataResult.Empty ->
                    recordApiFailure(
                            serverId = serverId,
                            issue =
                                    NetworkIssueClassifier.fromTransport(
                                            NetworkIssueKind.BusinessError,
                                            "realtime_connect_empty",
                                            result.reason.name
                                    )
                    )
            is DataResult.Error ->
                    recordApiFailure(
                            serverId = serverId,
                            issue =
                                    NetworkIssueClassifier.fromTransport(
                                            NetworkIssueKind.Unknown,
                                            result.error.code,
                                            "Realtime connect failed"
                                    )
                    )
            is DataResult.NoPermission ->
                    recordApiFailure(
                            serverId = serverId,
                            issue =
                                    NetworkIssueClassifier.fromTransport(
                                            NetworkIssueKind.Forbidden,
                                            result.blocker.code,
                                            result.blocker.requiredCapability
                                    )
                    )
        }
    }

    override fun onConnected(serverId: ServerId?, serverSeq: Long?) {
        applyRealtimeEvent(
                event = RealtimeConnectionEvent.Connected(nowMillis(), serverSeq),
                serverId = serverId
        )
    }

    override fun onRoomsJoined(serverId: ServerId?, lastSeq: Long?) {
        applyRealtimeEvent(
                event = RealtimeConnectionEvent.RoomsJoined(nowMillis()),
                serverId = serverId,
                lastSeq = lastSeq
        )
    }

    override fun onIntentionalDisconnect(reason: String, serverId: ServerId?) {
        update { current ->
            current.copy(
                    networkStatus =
                            if (reason == "background") current.networkStatus
                            else NetworkStatus.Unknown,
                    serverId = serverId?.value ?: current.serverId,
                    realtime =
                            current.realtime.copy(
                                    phase = RealtimeConnectionPhase.Disconnected,
                                    lastDisconnectedAtMillis = nowMillis(),
                                    lastDisconnectReason = reason,
                                    nextRetryDelayMillis = null,
                                    issue = null
                            )
            )
        }
    }

    override fun onDisconnected(reason: String, serverId: ServerId?) {
        applyRealtimeEvent(
                event =
                        RealtimeConnectionEvent.Disconnected(
                                nowMillis = nowMillis(),
                                reason = reason,
                                issue =
                                        NetworkIssueClassifier.fromTransport(
                                                NetworkIssueKind.ConnectionReset,
                                                "socket_disconnected",
                                                reason
                                        )
                        ),
                serverId = serverId
        )
    }

    override fun onConnectionFailed(reason: String, serverId: ServerId?) {
        applyRealtimeEvent(
                event =
                        RealtimeConnectionEvent.ConnectionFailed(
                                nowMillis = nowMillis(),
                                issue =
                                        NetworkIssueClassifier.fromTransport(
                                                NetworkIssueKind.ConnectionReset,
                                                "socket_connect_error",
                                                reason
                                        )
                        ),
                serverId = serverId
        )
    }

    override fun onEvent(event: RealtimeEvent, serverId: ServerId?, visibleThreadId: String?) {
        val now = nowMillis()
        val sharedEvent =
                when (event) {
                    is Heartbeat -> RealtimeConnectionEvent.Heartbeat(now, event.seq)
                    is MessageNew ->
                            event.message.seq?.let { RealtimeConnectionEvent.EventEnvelope(it, now) }
                    is MessageUpdated ->
                            event.message.seq?.let { RealtimeConnectionEvent.EventEnvelope(it, now) }
                    is SyncResumeResponse ->
                            RealtimeConnectionEvent.EventEnvelope(event.currentSeq, now)
                    else -> null
                } ?: return
        applyRealtimeEvent(sharedEvent, serverId, visibleThreadId)
    }

    fun onSyncResult(serverId: ServerId?, resultLabel: String) {
        update { it.copy(serverId = serverId?.value ?: it.serverId, lastSyncResult = resultLabel) }
    }

    private fun applyRealtimeEvent(
            event: RealtimeConnectionEvent,
            serverId: ServerId?,
            visibleThreadId: String? = null,
            lastSeq: Long? = null
    ) {
        update { current ->
            val seededRealtime =
                    lastSeq?.takeIf { it > 0L }?.let {
                        current.realtime.copy(
                                lastSeq = maxOf(current.realtime.lastSeq ?: 0L, it),
                                serverMaxSeq = maxOf(current.realtime.serverMaxSeq ?: 0L, it)
                        )
                    }
                            ?: current.realtime
            val result =
                    reducer.reduce(
                            current = seededRealtime,
                            event = event,
                            visibleThreadId = visibleThreadId,
                            serverId = serverId?.value ?: current.serverId
                    )
            enqueueIntents(result.intents)
            current.copy(
                    networkStatus = NetworkStatus.Online,
                    serverId = serverId?.value ?: current.serverId,
                    realtime = result.state,
                    lastSyncResult =
                            result.intents.takeIf { it.isNotEmpty() }?.joinToString("|") {
                                it::class.simpleName.orEmpty()
                            } ?: current.lastSyncResult
            )
        }
    }

    private fun recordApiFailure(serverId: ServerId, issue: NetworkIssue) {
        update { current ->
            current.copy(
                    networkStatus =
                            if (issue.retryable) NetworkStatus.Degraded else current.networkStatus,
                    apiReachable = false,
                    serverId = serverId.value,
                    lastApiFailureAtMillis = nowMillis(),
                    lastApiIssue = issue
            )
        }
    }

    private fun update(transform: (NetworkDiagnosticsState) -> NetworkDiagnosticsState) {
        val next = stateRef.updateAndGet(transform)
        logger(next.toSnapshot(nowMillis()).toLogLine())
    }

    private fun enqueueIntents(intents: List<RealtimeConnectionIntent>) {
        if (intents.isEmpty()) return
        pendingIntentsRef.updateAndGet { current -> current + intents }
    }

    private fun NetworkDiagnosticsState.toSnapshot(capturedAtMillis: Long):
            NetworkDiagnosticsSnapshot =
            NetworkDiagnosticsSnapshot(
                    capturedAtMillis = capturedAtMillis,
                    baseUrl = baseUrl,
                    socketUrl = socketUrl,
                    serverId = serverId,
                    buildLabel = buildLabel,
                    health =
                            ConnectionHealth(
                                    networkStatus = networkStatus,
                                    apiReachable = apiReachable,
                                    realtime = realtime,
                                    lastApiSuccessAtMillis = lastApiSuccessAtMillis,
                                    lastApiFailureAtMillis = lastApiFailureAtMillis,
                                    lastApiIssue = lastApiIssue
                            ),
                    pendingOutboxCount = pendingOutboxCount,
                    failedOutboxCount = failedOutboxCount,
                    lastSyncResult = lastSyncResult
            )
}

private data class NetworkDiagnosticsState(
        val networkStatus: NetworkStatus = NetworkStatus.Unknown,
        val apiReachable: Boolean? = null,
        val serverId: String? = null,
        val realtime: RealtimeConnectionState = RealtimeConnectionState(),
        val lastApiSuccessAtMillis: Long? = null,
        val lastApiFailureAtMillis: Long? = null,
        val lastApiIssue: NetworkIssue? = null,
        val pendingOutboxCount: Int = 0,
        val failedOutboxCount: Int = 0,
        val lastSyncResult: String? = null
)

private fun NetworkDiagnosticsSnapshot.toLogLine(): String =
        "snapshot serverId=${serverId.orEmpty()} " +
                "network=${health.networkStatus} " +
                "apiReachable=${health.apiReachable} " +
                "realtime=${health.realtime.phase} " +
                "lastSeq=${health.realtime.lastSeq ?: 0L} " +
                "serverMaxSeq=${health.realtime.serverMaxSeq ?: 0L} " +
                "heartbeatAt=${health.realtime.lastHeartbeatAtMillis ?: 0L} " +
                "issue=${health.realtime.issue?.diagnosticCode ?: health.lastApiIssue?.diagnosticCode.orEmpty()} " +
                "sync=${lastSyncResult.orEmpty()}"
