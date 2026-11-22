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
}
