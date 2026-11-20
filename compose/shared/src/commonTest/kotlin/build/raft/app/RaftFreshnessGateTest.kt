package build.raft.app

import build.raft.app.data.RAFT_FRESHNESS_COALESCE_MILLIS
import build.raft.app.data.RaftFreshnessEvent
import build.raft.app.data.RaftFreshnessGate
import build.raft.app.data.RaftFreshnessSignal
import build.raft.app.data.RaftFreshnessTrigger
import build.raft.app.data.RaftStaleReadRecoverySignals
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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
    fun aReconnectWithoutAnEpochIsCoalescedLikeTheOtherEvents() {
        val clock = Clock()
        val gate = RaftFreshnessGate(nowMillis = { clock.now })
        assertEquals(RaftFreshnessTrigger.Reconnect, gate.onEvent(reconnect("server-1", epoch = null), "server-1", visible = true))
        clock.now += 3_000
        assertNull(gate.onEvent(reconnect("server-1", epoch = null), "server-1", visible = true))
        clock.now += RAFT_FRESHNESS_COALESCE_MILLIS
        assertEquals(RaftFreshnessTrigger.Reconnect, gate.onEvent(reconnect("server-1", epoch = null), "server-1", visible = true))
    }

    @Test
    fun aReconnectOfTheServerThePageMovedToIsNotHiddenByThePreviousServers() {
        val clock = Clock()
        val gate = RaftFreshnessGate(nowMillis = { clock.now })
        assertEquals(RaftFreshnessTrigger.Reconnect, gate.onEvent(reconnect("server-a", epoch = 4), "server-a", visible = true))
        clock.now += 3_000
        // The page now shows server B; B's connection happens to carry the same number.
        assertEquals(
            RaftFreshnessTrigger.Reconnect,
            gate.onEvent(reconnect("server-b", epoch = 4), "server-b", visible = true),
            "each server has its own connections",
        )
        assertNull(gate.onEvent(reconnect("server-b", epoch = 4), "server-b", visible = true), "B's rooms:joined")
        assertNull(gate.onEvent(reconnect("server-a", epoch = 4), "server-a", visible = true), "back on A: still the same socket")
        assertEquals(RaftFreshnessTrigger.Reconnect, gate.onEvent(reconnect("server-a", epoch = 5), "server-a", visible = true))
    }

    @Test
    fun aReconnectWhileHiddenReplacesAWeakerKeptEventAndFiresInsideTheWindow() {
        val clock = Clock()
        val gate = RaftFreshnessGate(nowMillis = { clock.now })
        assertEquals(RaftFreshnessTrigger.Foreground, gate.onEvent(foreground, "server-1", visible = true))
        clock.now += 1_000
        assertNull(gate.onEvent(network, "server-1", visible = false))
        assertNull(gate.onEvent(reconnect("server-1", epoch = 7), "server-1", visible = false), "kept, not dropped")
        assertNull(gate.onEvent(reconnect("server-1", epoch = 7), "server-1", visible = false), "its rooms:joined")
        clock.now += 1_000
        assertEquals(
            RaftFreshnessTrigger.Reconnect,
            gate.onVisibilityChanged(visible = true),
            "the missed pushes are caught up although a catch-up ran 2 s ago",
        )
        assertNull(gate.onVisibilityChanged(visible = true), "only once")
        assertNull(gate.onEvent(reconnect("server-1", epoch = 7), "server-1", visible = true), "that connection is done")
    }

    @Test
    fun aWeakerEventKeptWhileHiddenIsStillCoalesced() {
        val clock = Clock()
        val gate = RaftFreshnessGate(nowMillis = { clock.now })
        assertEquals(RaftFreshnessTrigger.Foreground, gate.onEvent(foreground, "server-1", visible = true))
        clock.now += 1_000
        assertNull(gate.onEvent(network, "server-1", visible = false))
        assertNull(gate.onVisibilityChanged(visible = true), "the catch-up of 1 s ago covers it")
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
        RaftFreshnessSignal.onReconnected("  ", transportEpoch = 3)
        RaftFreshnessSignal.onReconnected(" server-9 ", transportEpoch = 3)
        assertEquals(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, "server-9", 3), received.await())
    }

    @Test
    fun theProcessComingBackAndTheNetworkComingBackReachSubscribers() = runTest {
        val received = async { RaftFreshnessSignal.events.take(2).toList() }
        yield()
        RaftStaleReadRecoverySignals.onProcessVisibility(foreground = false)
        RaftStaleReadRecoverySignals.onProcessVisibility(foreground = true)
        RaftStaleReadRecoverySignals.onProcessVisibility(foreground = true)
        RaftFreshnessSignal.onNetworkRestored()
        assertEquals(listOf(foreground, network), received.await(), "one event per return to the foreground")
    }
}
