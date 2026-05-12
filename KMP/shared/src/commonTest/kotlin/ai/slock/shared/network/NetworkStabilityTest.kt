package ai.slock.shared.network

import ai.slock.shared.network.model.BackfillScope
import ai.slock.shared.network.model.ConnectionHealth
import ai.slock.shared.network.model.NetworkDiagnosticsSnapshot
import ai.slock.shared.network.model.NetworkIssue
import ai.slock.shared.network.model.NetworkIssueClassifier
import ai.slock.shared.network.model.NetworkIssueKind
import ai.slock.shared.network.model.NetworkStatus
import ai.slock.shared.network.model.OutboxOperation
import ai.slock.shared.network.model.OutboxOperationStatus
import ai.slock.shared.network.model.OutboxOperationType
import ai.slock.shared.network.model.RealtimeConnectionPhase
import ai.slock.shared.network.model.RealtimeConnectionState
import ai.slock.shared.network.realtime.RealtimeConnectionEvent
import ai.slock.shared.network.realtime.RealtimeConnectionIntent
import ai.slock.shared.network.realtime.RealtimeConnectionReducer
import ai.slock.shared.network.retry.BackoffCalculator
import ai.slock.shared.network.retry.RetryPolicy
import ai.slock.shared.network.sync.SyncRecoveryCoordinator
import ai.slock.shared.network.sync.SyncRecoveryEvent
import ai.slock.shared.network.sync.SyncRecoveryIntent
import ai.slock.shared.network.sync.SyncRecoveryPhase
import ai.slock.shared.network.sync.SyncRecoveryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkStabilityTest {
    @Test
    fun httpStatusMapsToRetryableAndNonRetryableIssues() {
        val unauthorized = NetworkIssueClassifier.fromHttpStatus(401)
        assertEquals(NetworkIssueKind.Unauthorized, unauthorized.kind)
        assertFalse(unauthorized.retryable)

        val forbidden = NetworkIssueClassifier.fromHttpStatus(403)
        assertEquals(NetworkIssueKind.Forbidden, forbidden.kind)
        assertFalse(forbidden.retryable)

        val rateLimited = NetworkIssueClassifier.fromHttpStatus(
            statusCode = 429,
            serverCode = "rate_limited",
            retryAfterMillis = 3_000
        )
        assertEquals(NetworkIssueKind.RateLimited, rateLimited.kind)
        assertTrue(rateLimited.retryable)
        assertEquals("rate_limited", rateLimited.diagnosticCode)
        assertEquals(3_000, rateLimited.retryAfterMillis)

        val serverError = NetworkIssueClassifier.fromHttpStatus(503)
        assertEquals(NetworkIssueKind.ServerUnavailable, serverError.kind)
        assertTrue(serverError.retryable)

        val businessError = NetworkIssueClassifier.fromHttpStatus(422, serverCode = "bad_task")
        assertEquals(NetworkIssueKind.BusinessError, businessError.kind)
        assertFalse(businessError.retryable)
        assertEquals("bad_task", businessError.diagnosticCode)
    }

    @Test
    fun backoffUsesExponentialDelayAndRetryAfterOverride() {
        val calculator = BackoffCalculator()
        val policy = RetryPolicy(
            maxAttempts = 3,
            baseDelayMillis = 500,
            maxDelayMillis = 5_000,
            jitterRatio = 0.0
        )
        assertEquals(500, calculator.delayMillis(0, policy))
        assertEquals(1_000, calculator.delayMillis(1, policy))
        assertEquals(2_000, calculator.delayMillis(2, policy))
        assertNull(calculator.delayMillis(3, policy))

        val retryAfter = NetworkIssueClassifier.fromHttpStatus(429, retryAfterMillis = 9_000)
        assertEquals(5_000, calculator.delayMillis(0, policy, retryAfter))
        assertTrue(calculator.shouldRetry(0, policy, retryAfter))
        assertFalse(calculator.shouldRetry(3, policy, retryAfter))
    }

    @Test
    fun realtimeDisconnectSchedulesReconnectWithBackoff() {
        val reducer = RealtimeConnectionReducer(
            retryPolicy = RetryPolicy(
                maxAttempts = 3,
                baseDelayMillis = 1_000,
                maxDelayMillis = 8_000,
                jitterRatio = 0.0
            )
        )
        val result = reducer.reduce(
            current = RealtimeConnectionState(
                phase = RealtimeConnectionPhase.Connected,
                lastSeq = 41
            ),
            event = RealtimeConnectionEvent.Disconnected(
                nowMillis = 10_000,
                reason = "transport_closed"
            )
        )

        assertEquals(RealtimeConnectionPhase.Reconnecting, result.state.phase)
        assertEquals(1, result.state.reconnectAttempt)
        assertEquals(1_000, result.state.nextRetryDelayMillis)
        val connectIntent = assertIs<RealtimeConnectionIntent.Connect>(result.intents.single())
        assertEquals(1_000, connectIntent.delayMillis)
    }

    @Test
    fun realtimeReconnectTriggersResumeAndDefaultBackfill() {
        val reducer = RealtimeConnectionReducer()
        val result = reducer.reduce(
            current = RealtimeConnectionState(
                phase = RealtimeConnectionPhase.Reconnecting,
                reconnectAttempt = 2,
                lastSeq = 123
            ),
            event = RealtimeConnectionEvent.Connected(nowMillis = 20_000),
            serverId = "server-1",
            visibleThreadId = "thread-1"
        )

        assertEquals(RealtimeConnectionPhase.Connected, result.state.phase)
        assertEquals(0, result.state.reconnectAttempt)
        assertTrue(result.intents.contains(RealtimeConnectionIntent.ResumeSync(123)))
        assertTrue(
            result.intents.contains(
                RealtimeConnectionIntent.BackfillRequired(BackfillScope.Home("server-1"), "reconnected")
            )
        )
        assertTrue(
            result.intents.contains(
                RealtimeConnectionIntent.BackfillRequired(BackfillScope.Thread("thread-1"), "reconnected")
            )
        )
    }

    @Test
    fun realtimeSeqGapTriggersBackfillIntents() {
        val reducer = RealtimeConnectionReducer()
        val result = reducer.reduce(
            current = RealtimeConnectionState(
                phase = RealtimeConnectionPhase.Connected,
                lastSeq = 10
            ),
            event = RealtimeConnectionEvent.EventEnvelope(seq = 13, nowMillis = 30_000),
            serverId = "server-1",
            visibleThreadId = "thread-2"
        )

        assertEquals(13, result.state.lastSeq)
        assertEquals(
            listOf(
                RealtimeConnectionIntent.BackfillRequired(BackfillScope.Home("server-1"), "seq_gap"),
                RealtimeConnectionIntent.BackfillRequired(BackfillScope.Thread("thread-2"), "seq_gap")
            ),
            result.intents
        )
    }

    @Test
    fun syncRecoveryScopesResumeAndGapBackfills() {
        val coordinator = SyncRecoveryCoordinator()
        val resume = coordinator.reduce(
            state = SyncRecoveryState(),
            event = SyncRecoveryEvent.ResumeRequested(
                serverId = "server-1",
                visibleThreadId = "thread-1",
                afterSeq = 5
            )
        )

        assertEquals(SyncRecoveryPhase.Backfilling, resume.state.phase)
        assertTrue(resume.intents.contains(SyncRecoveryIntent.ResumeRealtime(5)))
        assertTrue(resume.intents.contains(SyncRecoveryIntent.Backfill(BackfillScope.Home("server-1"))))
        assertTrue(resume.intents.contains(SyncRecoveryIntent.Backfill(BackfillScope.Thread("thread-1"))))

        val started = coordinator.reduce(
            state = resume.state,
            event = SyncRecoveryEvent.BackfillStarted(BackfillScope.Home("server-1"))
        )
        assertEquals(BackfillScope.Home("server-1"), started.state.activeBackfill)

        val succeeded = coordinator.reduce(
            state = started.state,
            event = SyncRecoveryEvent.BackfillSucceeded(BackfillScope.Home("server-1"), nowMillis = 42)
        )
        assertEquals(SyncRecoveryPhase.Backfilling, succeeded.state.phase)
        assertEquals(listOf(SyncRecoveryIntent.Backfill(BackfillScope.Thread("thread-1"))), succeeded.intents)
    }

    @Test
    fun outboxLoadedTriggersDrainWhenPendingOrFailedExists() {
        val coordinator = SyncRecoveryCoordinator()
        val operation = OutboxOperation(
            id = "op-1",
            type = OutboxOperationType.SendMessage,
            idempotencyKey = "client-message-1",
            scope = BackfillScope.Thread("thread-1"),
            createdAtMillis = 1,
            status = OutboxOperationStatus.Failed,
            lastIssue = retryableIssue()
        )

        val result = coordinator.reduce(
            state = SyncRecoveryState(),
            event = SyncRecoveryEvent.OutboxLoaded(listOf(operation))
        )

        assertEquals(listOf(SyncRecoveryIntent.DrainOutbox), result.intents)
    }

    @Test
    fun diagnosticsSnapshotRedactsUrlQueryAndKeepsCoreFields() {
        val snapshot = NetworkDiagnosticsSnapshot(
            capturedAtMillis = 100,
            baseUrl = "https://api.example.test?token=secret",
            socketUrl = "wss://socket.example.test?key=secret",
            serverId = "server-1",
            buildLabel = "debug",
            health = ConnectionHealth(
                networkStatus = NetworkStatus.Offline,
                apiReachable = false,
                realtime = RealtimeConnectionState(
                    phase = RealtimeConnectionPhase.Reconnecting,
                    reconnectAttempt = 2,
                    lastSeq = 9,
                    lastDisconnectReason = "network_offline"
                ),
                lastApiIssue = retryableIssue()
            ),
            pendingOutboxCount = 3,
            failedOutboxCount = 1,
            lastSyncResult = "gap_backfill_failed"
        )

        val text = snapshot.toRedactedText()
        assertTrue(text.contains("network_status=Offline"))
        assertTrue(text.contains("realtime_phase=Reconnecting"))
        assertTrue(text.contains("last_seq=9"))
        assertTrue(text.contains("pending_outbox_count=3"))
        assertTrue(text.contains("base_url=https://api.example.test"))
        assertFalse(text.contains("secret"))
    }

    private fun retryableIssue(): NetworkIssue =
        NetworkIssueClassifier.fromTransport(
            kind = NetworkIssueKind.Timeout,
            diagnosticCode = "timeout"
        )
}
