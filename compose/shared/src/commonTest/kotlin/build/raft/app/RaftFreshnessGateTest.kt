package build.raft.app

import build.raft.app.data.RAFT_FRESHNESS_COALESCE_MILLIS
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
}
