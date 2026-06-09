package ai.slock.android.core.data.diagnostics

import ai.slock.compose.data.DataResult
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.Heartbeat
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.MessageNew
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.state.ScreenError
import ai.slock.compose.network.model.BackfillScope
import ai.slock.compose.network.model.NetworkStatus
import ai.slock.compose.network.model.RealtimeConnectionPhase
import ai.slock.compose.network.realtime.RealtimeConnectionIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDiagnosticsBridgeTest {
    @Test
    fun realtimeEventsUpdateKmpDiagnosticsSnapshot() {
        var now = 1_000L
        val logs = mutableListOf<String>()
        val bridge =
                NetworkDiagnosticsBridge(
                        nowMillis = { now },
                        logger = { logs += it }
                )
        val serverId = ServerId("server-1")

        bridge.onConnectRequested(serverId, lastSeq = 41)
        bridge.onRealtimeConnectResult(serverId, DataResult.Content(Unit))
        now = 2_000L
        bridge.onConnected(serverId, serverSeq = 41)
        now = 3_000L
        bridge.onEvent(Heartbeat(seq = 42), serverId, visibleThreadId = "thread-1")
        now = 4_000L
        bridge.onEvent(MessageNew(message(seq = 43)), serverId, visibleThreadId = "thread-1")

        val snapshot = bridge.snapshot()

        assertEquals(NetworkStatus.Online, snapshot.health.networkStatus)
        assertEquals(true, snapshot.health.apiReachable)
        assertEquals("server-1", snapshot.serverId)
        assertEquals(RealtimeConnectionPhase.Connected, snapshot.health.realtime.phase)
        assertEquals(43L, snapshot.health.realtime.lastSeq)
        assertEquals(43L, snapshot.health.realtime.serverMaxSeq)
        assertEquals(3_000L, snapshot.health.realtime.lastHeartbeatAtMillis)
        assertTrue(
                logs.any {
                    it.contains("realtime=Connected") &&
                            it.contains("lastSeq=43") &&
                            it.contains("serverMaxSeq=43")
                }
        )
    }

    @Test
    fun heartbeatGapDoesNotAdvanceResumeSeqAndQueuesBackfill() {
        var now = 1_000L
        val bridge = NetworkDiagnosticsBridge(nowMillis = { now }, logger = {})
        val serverId = ServerId("server-1")

        bridge.onConnectRequested(serverId, lastSeq = 41)
        bridge.onConnected(serverId, serverSeq = null)
        bridge.drainPendingIntents()
        now = 2_000L
        bridge.onEvent(Heartbeat(seq = 44), serverId, visibleThreadId = "thread-1")

        val snapshot = bridge.snapshot()
        val intents = bridge.drainPendingIntents()

        assertEquals(41L, snapshot.health.realtime.lastSeq)
        assertEquals(44L, snapshot.health.realtime.serverMaxSeq)
        assertTrue(
                intents.contains(
                        RealtimeConnectionIntent.BackfillRequired(
                                BackfillScope.Home("server-1"),
                                "heartbeat_gap"
                        )
                )
        )
        assertTrue(
                intents.contains(
                        RealtimeConnectionIntent.BackfillRequired(
                                BackfillScope.Thread("thread-1"),
                                "heartbeat_gap"
                        )
                )
        )
    }

    @Test
    fun connectFailureRecordsApiIssueWithoutChangingUiBehavior() {
        val bridge = NetworkDiagnosticsBridge(nowMillis = { 5_000L }, logger = {})
        val serverId = ServerId("server-1")

        bridge.onRealtimeConnectResult(
                serverId,
                DataResult.Error(ScreenError(code = "network_timeout", retryable = true))
        )

        val snapshot = bridge.snapshot()

        assertEquals(false, snapshot.health.apiReachable)
        assertEquals("network_timeout", snapshot.health.lastApiIssue?.diagnosticCode)
        assertFalse(snapshot.toRedactedText().contains("token="))
    }

    @Test
    fun transportFailureExposesKmpRetryIntent() {
        val bridge = NetworkDiagnosticsBridge(nowMillis = { 1_000L }, logger = {})
        val serverId = ServerId("server-1")

        bridge.onConnectRequested(serverId, lastSeq = 12)
        bridge.onConnectionFailed(reason = "timeout", serverId = serverId)

        val intents = bridge.drainPendingIntents()

        val retry = intents.filterIsInstance<RealtimeConnectionIntent.Connect>().last()
        assertTrue(retry.delayMillis > 0L)
        assertEquals(emptyList<RealtimeConnectionIntent>(), bridge.drainPendingIntents())
    }

    private fun message(seq: Long): Message =
            Message(
                    id = MessageId("message-$seq"),
                    threadId = ThreadId("thread-1"),
                    channelId = ChannelId("thread-1"),
                    seq = seq,
                    author =
                            MemberProfile(
                                    id = MemberId("member-1"),
                                    displayName = "Member One",
                                    handle = "member-one",
                                    kind = MemberKind.Human
                            ),
                    timestampLabel = "12:00",
                    body = "Message $seq"
            )
}
