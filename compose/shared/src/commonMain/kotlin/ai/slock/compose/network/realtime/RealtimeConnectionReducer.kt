package ai.slock.compose.network.realtime

import ai.slock.compose.network.model.BackfillScope
import ai.slock.compose.network.model.NetworkIssue
import ai.slock.compose.network.model.NetworkIssueKind
import ai.slock.compose.network.model.NetworkStatus
import ai.slock.compose.network.model.RealtimeConnectionPhase
import ai.slock.compose.network.model.RealtimeConnectionState
import ai.slock.compose.network.retry.BackoffCalculator
import ai.slock.compose.network.retry.RetryPolicy

sealed class RealtimeConnectionEvent {
    data class NetworkChanged(val status: NetworkStatus, val nowMillis: Long) :
        RealtimeConnectionEvent()

    data class ConnectRequested(val nowMillis: Long) : RealtimeConnectionEvent()

    data class Connected(val nowMillis: Long, val serverSeq: Long? = null) :
        RealtimeConnectionEvent()

    data class Disconnected(
        val nowMillis: Long,
        val reason: String,
        val issue: NetworkIssue? = null
    ) : RealtimeConnectionEvent()

    data class ConnectionFailed(val nowMillis: Long, val issue: NetworkIssue) :
        RealtimeConnectionEvent()

    data class Heartbeat(val nowMillis: Long, val seq: Long) : RealtimeConnectionEvent()

    data class EventEnvelope(val seq: Long, val nowMillis: Long) : RealtimeConnectionEvent()
}

sealed class RealtimeConnectionIntent {
    data class Connect(val delayMillis: Long = 0) : RealtimeConnectionIntent()
    data object Disconnect : RealtimeConnectionIntent()
    data class ResumeSync(val afterSeq: Long) : RealtimeConnectionIntent()
    data class ReplayRooms(val reason: String) : RealtimeConnectionIntent()
    data class BackfillRequired(val scope: BackfillScope, val reason: String) :
        RealtimeConnectionIntent()
}

data class RealtimeReducerResult(
    val state: RealtimeConnectionState,
    val intents: List<RealtimeConnectionIntent> = emptyList()
)

class RealtimeConnectionReducer(
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val backoffCalculator: BackoffCalculator = BackoffCalculator()
) {
    fun reduce(
        current: RealtimeConnectionState,
        event: RealtimeConnectionEvent,
        visibleThreadId: String? = null,
        serverId: String? = null
    ): RealtimeReducerResult =
        when (event) {
            is RealtimeConnectionEvent.NetworkChanged ->
                reduceNetworkChanged(current, event)

            is RealtimeConnectionEvent.ConnectRequested ->
                RealtimeReducerResult(
                    state = current.copy(
                        phase = RealtimeConnectionPhase.Connecting,
                        issue = null,
                        nextRetryDelayMillis = null
                    ),
                    intents = listOf(RealtimeConnectionIntent.Connect())
                )

            is RealtimeConnectionEvent.Connected -> {
                val resumeSeq = current.lastSeq
                val intents = buildList {
                    if (resumeSeq != null) add(RealtimeConnectionIntent.ResumeSync(resumeSeq))
                    serverId?.let { add(RealtimeConnectionIntent.ReplayRooms("reconnected")) }
                    serverId?.let {
                        add(RealtimeConnectionIntent.BackfillRequired(BackfillScope.Home(it), "reconnected"))
                    }
                    visibleThreadId?.let {
                        add(RealtimeConnectionIntent.BackfillRequired(BackfillScope.Thread(it), "reconnected"))
                    }
                }
                RealtimeReducerResult(
                    state = current.copy(
                        phase = RealtimeConnectionPhase.Connected,
                        reconnectAttempt = 0,
                        lastSeq = event.serverSeq ?: current.lastSeq,
                        lastConnectedAtMillis = event.nowMillis,
                        lastDisconnectReason = null,
                        nextRetryDelayMillis = null,
                        issue = null
                    ),
                    intents = intents
                )
            }

            is RealtimeConnectionEvent.Disconnected ->
                scheduleReconnect(
                    current = current,
                    nowMillis = event.nowMillis,
                    reason = event.reason,
                    issue = event.issue ?: transportIssue("socket_disconnected", event.reason)
                )

            is RealtimeConnectionEvent.ConnectionFailed ->
                scheduleReconnect(
                    current = current,
                    nowMillis = event.nowMillis,
                    reason = event.issue.diagnosticCode,
                    issue = event.issue
                )

            is RealtimeConnectionEvent.Heartbeat ->
                RealtimeReducerResult(
                    state = current.copy(
                        phase = RealtimeConnectionPhase.Connected,
                        lastSeq = maxSeq(current.lastSeq, event.seq),
                        lastHeartbeatAtMillis = event.nowMillis,
                        issue = null
                    )
                )

            is RealtimeConnectionEvent.EventEnvelope -> {
                val expected = current.lastSeq?.plus(1)
                val gap = expected != null && event.seq > expected
                val intents = buildList {
                    if (gap) {
                        serverId?.let {
                            add(RealtimeConnectionIntent.BackfillRequired(BackfillScope.Home(it), "seq_gap"))
                        }
                        visibleThreadId?.let {
                            add(RealtimeConnectionIntent.BackfillRequired(BackfillScope.Thread(it), "seq_gap"))
                        }
                    }
                }
                RealtimeReducerResult(
                    state = current.copy(lastSeq = maxSeq(current.lastSeq, event.seq)),
                    intents = intents
                )
            }
        }

    private fun reduceNetworkChanged(
        current: RealtimeConnectionState,
        event: RealtimeConnectionEvent.NetworkChanged
    ): RealtimeReducerResult =
        when (event.status) {
            NetworkStatus.Offline ->
                RealtimeReducerResult(
                    state = current.copy(
                        phase = RealtimeConnectionPhase.Disconnected,
                        lastDisconnectedAtMillis = event.nowMillis,
                        lastDisconnectReason = "network_offline",
                        issue = transportIssue("network_offline", "Network offline"),
                        nextRetryDelayMillis = null
                    ),
                    intents = listOf(RealtimeConnectionIntent.Disconnect)
                )
            NetworkStatus.Online, NetworkStatus.Degraded ->
                if (current.phase == RealtimeConnectionPhase.Disconnected ||
                    current.phase == RealtimeConnectionPhase.Failed
                ) {
                    RealtimeReducerResult(
                        state = current.copy(
                            phase = RealtimeConnectionPhase.Reconnecting,
                            nextRetryDelayMillis = 0
                        ),
                        intents = listOf(RealtimeConnectionIntent.Connect())
                    )
                } else {
                    RealtimeReducerResult(current)
                }
            NetworkStatus.Unknown -> RealtimeReducerResult(current)
        }

    private fun scheduleReconnect(
        current: RealtimeConnectionState,
        nowMillis: Long,
        reason: String,
        issue: NetworkIssue
    ): RealtimeReducerResult {
        if (!issue.retryable) {
            return RealtimeReducerResult(
                state = current.copy(
                    phase = RealtimeConnectionPhase.Failed,
                    lastDisconnectedAtMillis = nowMillis,
                    lastDisconnectReason = reason,
                    issue = issue,
                    nextRetryDelayMillis = null
                )
            )
        }
        val attempt = current.reconnectAttempt + 1
        val delay = backoffCalculator.delayMillis(attempt - 1, retryPolicy, issue)
        return if (delay == null) {
            RealtimeReducerResult(
                state = current.copy(
                    phase = RealtimeConnectionPhase.Failed,
                    reconnectAttempt = attempt,
                    lastDisconnectedAtMillis = nowMillis,
                    lastDisconnectReason = reason,
                    issue = issue,
                    nextRetryDelayMillis = null
                )
            )
        } else {
            RealtimeReducerResult(
                state = current.copy(
                    phase = RealtimeConnectionPhase.Reconnecting,
                    reconnectAttempt = attempt,
                    lastDisconnectedAtMillis = nowMillis,
                    lastDisconnectReason = reason,
                    issue = issue,
                    nextRetryDelayMillis = delay
                ),
                intents = listOf(RealtimeConnectionIntent.Connect(delay))
            )
        }
    }

    private fun transportIssue(code: String, message: String): NetworkIssue =
        NetworkIssue(
            kind = NetworkIssueKind.ConnectionReset,
            retryable = true,
            userVisible = true,
            diagnosticCode = code,
            message = message
        )

    private fun maxSeq(current: Long?, next: Long): Long =
        current?.let { maxOf(it, next) } ?: next
}
