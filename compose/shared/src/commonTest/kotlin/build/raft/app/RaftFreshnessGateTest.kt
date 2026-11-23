package build.raft.app

import build.raft.app.data.RAFT_FRESHNESS_COALESCE_MILLIS
import build.raft.app.data.RAFT_FRESHNESS_CONNECTED_FOREGROUND_SKIP_MILLIS
import build.raft.app.data.RaftFreshnessEvent
import build.raft.app.data.RaftFreshnessGate
import build.raft.app.data.RaftFreshnessTrigger
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #proj-mobile:#198 ②: one freshness signal, at most one catch-up per return per subscriber. */
class RaftFreshnessGateTest {
    private val foreground = RaftFreshnessEvent(RaftFreshnessTrigger.Foreground)
    private val network = RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored)
    private fun reconnect(serverId: String, epoch: Long? = 1L) = RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, serverId, epoch)

    private class Clock(var now: Long = 1_000_000L)

    @Test
    fun oneReturnIsOneCatchUp() {
        val clock = Clock()
        val gate = RaftFreshnessGate(nowMillis = { clock.now })
        assertEquals(RaftFreshnessTrigger.Foreground, gate.onEvent(foreground, "server-1", visible = true))
        clock.now += 2_000
        assertNull(gate.onEvent(network, "server-1", visible = true), "network of the same return")
        clock.now += RAFT_FRESHNESS_COALESCE_MILLIS
        assertEquals(RaftFreshnessTrigger.Foreground, gate.onEvent(foreground, "server-1", visible = true), "next return")
    }

    @Test
    fun aReconnectOfThePagesServerAlwaysCatchesUpOncePerConnection() {
        val clock = Clock()
        val gate = RaftFreshnessGate(nowMillis = { clock.now })
        assertEquals(RaftFreshnessTrigger.Foreground, gate.onEvent(foreground, "server-1", visible = true))
        clock.now += 1_500
        assertNull(gate.onEvent(reconnect("server-2"), "server-1", visible = true), "another server")
        assertEquals(
            RaftFreshnessTrigger.Reconnect,
            gate.onEvent(reconnect("server-1"), "server-1", visible = true),
            "pushes resume: catch up again even right after the foreground one",
        )
        clock.now += 3_000
        assertNull(gate.onEvent(reconnect("server-1"), "server-1", visible = true), "rooms:joined of the same socket")
        assertNull(gate.onEvent(foreground, "server-1", visible = true), "just caught up")
        clock.now += RAFT_FRESHNESS_COALESCE_MILLIS * 6
        assertNull(gate.onEvent(reconnect("server-1"), "server-1", visible = true), "the same socket, however late")
        assertNull(RaftFreshnessGate(nowMillis = { clock.now }).onEvent(reconnect("server-1"), null, visible = true), "no server")
    }

    @Test
    fun aSocketThatFlapsInsideTheWindowCatchesUpForEachConnection() {
        // Connect at 0 s, drop at 2 s, reconnect at 6 s: the pushes of those 4 s were missed.
        val clock = Clock()
        val gate = RaftFreshnessGate(nowMillis = { clock.now })
        assertEquals(RaftFreshnessTrigger.Reconnect, gate.onEvent(reconnect("server-1", epoch = 1), "server-1", visible = true))
        clock.now += 6_000
        assertEquals(
            RaftFreshnessTrigger.Reconnect,
            gate.onEvent(reconnect("server-1", epoch = 2), "server-1", visible = true),
            "a new connection inside the window",
        )
        assertNull(gate.onEvent(reconnect("server-1", epoch = 2), "server-1", visible = true), "its rooms:joined")
        assertNull(gate.onEvent(network, "server-1", visible = true), "the network event of the same return")
    }

    @Test
    fun anEventWhileHiddenFiresOnceThePageShowsAgain() {
        val clock = Clock()
        val gate = RaftFreshnessGate(nowMillis = { clock.now })
        assertNull(gate.onEvent(reconnect("server-1"), "server-1", visible = false))
        assertNull(gate.onEvent(foreground, "server-1", visible = false))
        assertNull(gate.onVisibilityChanged(visible = false))
        assertEquals(RaftFreshnessTrigger.Reconnect, gate.onVisibilityChanged(visible = true), "the first missed event")
        assertNull(gate.onVisibilityChanged(visible = true), "only once")
    }

    @Test
    fun failedTrackedCatchUpDoesNotConsumeCoalesceWindowAndAllowsNetworkRestored() {
        val clock = Clock()
        val gate = RaftFreshnessGate(nowMillis = { clock.now }, trackOutcome = true)

        assertEquals(RaftFreshnessTrigger.Foreground, gate.onEvent(foreground, "server-1", visible = true))
        clock.now += 500
        assertNull(gate.onCatchUpCompleted(succeeded = false, visible = true))

        // Network recovers 2 s later inside the 10 s window: must retry because foreground failed.
        clock.now += 2_000
        assertEquals(
            RaftFreshnessTrigger.NetworkRestored,
            gate.onEvent(network, "server-1", visible = true),
            "a failed foreground catch-up must not swallow a subsequent NetworkRestored",
        )
        clock.now += 300
        assertNull(gate.onCatchUpCompleted(succeeded = true, visible = true))

        // Once succeeded, redundant non-forced events within 10 s still coalesce.
        clock.now += 2_000
        assertNull(gate.onEvent(network, "server-1", visible = true), "already succeeded within 10 s")
    }

    @Test
    fun networkRestoredArrivingWhileFailingForegroundCatchUpRunsTriggersImmediateFollowUp() {
        val clock = Clock()
        val gate = RaftFreshnessGate(nowMillis = { clock.now }, trackOutcome = true)

        assertEquals(RaftFreshnessTrigger.Foreground, gate.onEvent(foreground, "server-1", visible = true))
        // Network comes back while the foreground request is still hanging on the old offline socket.
        clock.now += 1_500
        assertNull(gate.onEvent(network, "server-1", visible = true), "queued while foreground is in flight")
        // Foreground request fails: queued NetworkRestored must fire immediately.
        clock.now += 1_000
        assertEquals(
            RaftFreshnessTrigger.NetworkRestored,
            gate.onCatchUpCompleted(succeeded = false, visible = true),
            "queued NetworkRestored fires once the failing foreground read finishes",
        )
        assertNull(gate.onCatchUpCompleted(succeeded = true, visible = true))
    }

    @Test
    fun newReconnectDuringSucceededCatchUpStillTriggersFollowUp() {
        val clock = Clock()
        val gate = RaftFreshnessGate(nowMillis = { clock.now }, trackOutcome = true)

        assertEquals(RaftFreshnessTrigger.Foreground, gate.onEvent(foreground, "server-1", visible = true))
        clock.now += 1_000
        assertNull(gate.onEvent(reconnect("server-1", epoch = 2), "server-1", visible = true))
        clock.now += 500
        assertEquals(
            RaftFreshnessTrigger.Reconnect,
            gate.onCatchUpCompleted(succeeded = true, visible = true),
            "a new socket connection arriving mid-flight still triggers a post-reconnect catch-up",
        )
    }

    @Test
    fun connectedForegroundWithin60sSkipsUntilMinutePassesOrSocketDrops() {
        val clock = Clock()
        var liveToken: Long? = 42L
        val gate = RaftFreshnessGate(nowMillis = { clock.now }, liveConnectionToken = { liveToken })

        assertEquals(RaftFreshnessTrigger.Foreground, gate.onEvent(foreground, "server-1", visible = true))
        // Past the 10 s return coalesce, still < 60 s on the exact same unbroken connection: skip.
        clock.now += 25_000
        assertNull(
            gate.onEvent(foreground, "server-1", visible = true),
            "unbroken socket within 60 s skips redundant foreground check",
        )
        // Once 60 s has elapsed, foreground checks again even if the socket stayed connected.
        clock.now += RAFT_FRESHNESS_CONNECTED_FOREGROUND_SKIP_MILLIS - 25_000
        assertEquals(
            RaftFreshnessTrigger.Foreground,
            gate.onEvent(foreground, "server-1", visible = true),
            "foreground checks again once 60 s has elapsed",
        )
        // If the socket dropped (new token or null), a foreground return inside 60 s (past 10 s coalesce) catches up.
        clock.now += 20_000
        liveToken = 43L
        assertEquals(
            RaftFreshnessTrigger.Foreground,
            gate.onEvent(foreground, "server-1", visible = true),
            "a foreground return after a socket drop inside 60 s still catches up",
        )
    }

    @Test
    fun networkRestoredAndReconnectStillFireInside60sConnectedWindow() {
        val clock = Clock()
        val liveToken = 9L
        val gate = RaftFreshnessGate(nowMillis = { clock.now }, liveConnectionToken = { liveToken })

        assertEquals(RaftFreshnessTrigger.Foreground, gate.onEvent(foreground, "server-1", visible = true))
        clock.now += 15_000
        assertEquals(
            RaftFreshnessTrigger.NetworkRestored,
            gate.onEvent(network, "server-1", visible = true),
            "NetworkRestored is not suppressed by the 60 s unbroken-socket foreground skip",
        )
        clock.now += 5_000
        assertEquals(
            RaftFreshnessTrigger.Reconnect,
            gate.onEvent(reconnect("server-1", epoch = 2), "server-1", visible = true),
            "new Reconnect epoch still forces a catch-up",
        )
    }

    @Test
    fun trackedOutcomeFailureClearsConnectedSkipToken() {
        val clock = Clock()
        val liveToken = 7L
        val gate = RaftFreshnessGate(nowMillis = { clock.now }, trackOutcome = true, liveConnectionToken = { liveToken })

        assertEquals(RaftFreshnessTrigger.Foreground, gate.onEvent(foreground, "server-1", visible = true))
        clock.now += 500
        assertNull(gate.onCatchUpCompleted(succeeded = false, visible = true))
        clock.now += 15_000
        assertEquals(
            RaftFreshnessTrigger.Foreground,
            gate.onEvent(foreground, "server-1", visible = true),
            "a failed catch-up must never activate the 60 s unbroken-socket skip",
        )
    }
}
