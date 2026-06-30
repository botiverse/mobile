package build.raft.app.network.realtime

import build.raft.app.network.model.BackfillScope
import build.raft.app.network.model.RealtimeConnectionPhase
import build.raft.app.network.model.RealtimeConnectionState
import build.raft.app.data.realtime.RealtimeHeartbeatWatchdog
import build.raft.app.data.realtime.RealtimeHeartbeatWatchdogSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RealtimeConnectionReducerTest {
    private val reducer = RealtimeConnectionReducer()

    @Test
    fun connectedReplaysRoomsButDoesNotResumeBeforeRoomsJoined() {
        val state = RealtimeConnectionState(lastSeq = 41)

        val connected =
            reducer.reduce(
                current = state,
                event = RealtimeConnectionEvent.Connected(nowMillis = 1_000L),
                serverId = "server-1"
            )

        assertEquals(RealtimeConnectionPhase.Connected, connected.state.phase)
        assertEquals(41L, connected.state.lastSeq)
        assertFalse(connected.intents.any { it is RealtimeConnectionIntent.ResumeSync })
        assertTrue(connected.intents.any { it == RealtimeConnectionIntent.ReplayRooms("reconnected") })

        val roomsJoined =
            reducer.reduce(
                current = connected.state,
                event = RealtimeConnectionEvent.RoomsJoined(nowMillis = 1_500L),
                serverId = "server-1"
            )

        assertEquals(listOf(RealtimeConnectionIntent.ResumeSync(41)), roomsJoined.intents)
    }

    @Test
    fun heartbeatTracksServerMaxSeqWithoutAdvancingResumeSeq() {
        val state = RealtimeConnectionState(
            phase = RealtimeConnectionPhase.Connected,
            lastSeq = 41
        )

        val result =
            reducer.reduce(
                current = state,
                event = RealtimeConnectionEvent.Heartbeat(nowMillis = 2_000L, seq = 44),
                visibleThreadId = "thread-1",
                serverId = "server-1"
            )

        assertEquals(41L, result.state.lastSeq)
        assertEquals(44L, result.state.serverMaxSeq)
        assertEquals(2_000L, result.state.lastHeartbeatAtMillis)
        assertTrue(
            result.intents.contains(
                RealtimeConnectionIntent.BackfillRequired(BackfillScope.Home("server-1"), "heartbeat_gap")
            )
        )
        assertTrue(
            result.intents.contains(
                RealtimeConnectionIntent.BackfillRequired(BackfillScope.Thread("thread-1"), "heartbeat_gap")
            )
        )
    }

    @Test
    fun eventEnvelopeAdvancesAppliedAndServerSeqTogether() {
        val result =
            reducer.reduce(
                current = RealtimeConnectionState(lastSeq = 41, serverMaxSeq = 44),
                event = RealtimeConnectionEvent.EventEnvelope(seq = 45, nowMillis = 3_000L),
                serverId = "server-1"
            )

        assertEquals(45L, result.state.lastSeq)
        assertEquals(45L, result.state.serverMaxSeq)
    }

    @Test
    fun heartbeatWatchdogRequiresHeartbeatAndInboundSilence() {
        assertFalse(
            RealtimeHeartbeatWatchdog.shouldForceReconnect(
                RealtimeHeartbeatWatchdogSnapshot(
                    connected = true,
                    lastHeartbeatAtMillis = 1_000L,
                    lastInboundEventAtMillis = 80_000L
                ),
                nowMillis = 125_000L
            )
        )
        assertTrue(
            RealtimeHeartbeatWatchdog.shouldForceReconnect(
                RealtimeHeartbeatWatchdogSnapshot(
                    connected = true,
                    lastHeartbeatAtMillis = 1_000L,
                    lastInboundEventAtMillis = 1_000L
                ),
                nowMillis = 125_000L
            )
        )
    }
}
