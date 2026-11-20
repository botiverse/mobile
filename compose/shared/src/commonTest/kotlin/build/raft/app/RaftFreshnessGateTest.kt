package build.raft.app

import build.raft.app.data.RAFT_FRESHNESS_COALESCE_MILLIS
import build.raft.app.data.RaftFreshnessEvent
import build.raft.app.data.RaftFreshnessGate
import build.raft.app.data.RaftFreshnessSignal
import build.raft.app.data.RaftFreshnessTrigger
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #proj-mobile:#198 ②: one freshness signal, at most one catch-up per return per subscriber. */
class RaftFreshnessGateTest {
    private val foreground = RaftFreshnessEvent(RaftFreshnessTrigger.Foreground)
    private val network = RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored)
    private fun reconnect(serverId: String) = RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, serverId)

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
    fun aReconnectOfThePagesServerAlwaysCatchesUpOncePerWindow() {
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
        assertNull(RaftFreshnessGate(nowMillis = { clock.now }).onEvent(reconnect("server-1"), null, visible = true), "no server")
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
    fun aRouteThatAlreadyRefreshesOnForegroundOnlyTakesReconnects() {
        val clock = Clock()
        val gate = RaftFreshnessGate(foreground = false, nowMillis = { clock.now })
        assertNull(gate.onEvent(foreground, "server-1", visible = true))
        assertEquals(RaftFreshnessTrigger.Reconnect, gate.onEvent(reconnect("server-1"), "server-1", visible = true))
        clock.now += RAFT_FRESHNESS_COALESCE_MILLIS
        assertEquals(RaftFreshnessTrigger.NetworkRestored, gate.onEvent(network, "server-1", visible = true))
        assertNull(RaftFreshnessGate(reconnect = false, nowMillis = { clock.now }).onEvent(network, "server-1", visible = true))
    }

    @Test
    fun theSignalCarriesEachSourceToSubscribers() = runTest {
        val received = async { RaftFreshnessSignal.events.first { it.trigger == RaftFreshnessTrigger.Reconnect } }
        yield()
        RaftFreshnessSignal.onReconnected("  ")
        RaftFreshnessSignal.onReconnected(" server-9 ")
        assertEquals(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, "server-9"), received.await())
    }
}
