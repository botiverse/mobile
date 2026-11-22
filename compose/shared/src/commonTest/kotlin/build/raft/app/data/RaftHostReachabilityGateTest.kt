package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task #150 (Hands 6604f4c4): an unreachable API host must not be hammered by every page switch.
 */
class RaftHostReachabilityGateTest {
    private var now = 0L
    private fun gate() = RaftHostReachabilityGate(nowMillis = { now })

    @Test
    fun onlyDnsAndConnectFailuresWithoutStatusCountAsUnreachable() {
        assertTrue(failure("DNS").isHostUnreachableFailure())
        assertTrue(failure("CONNECT").isHostUnreachableFailure())
        assertFalse(failure("TIMEOUT").isHostUnreachableFailure())
        assertFalse(failure("TLS").isHostUnreachableFailure())
        assertFalse(failure("CANCELLED").isHostUnreachableFailure())
        assertFalse(HomeNetworkResponse(code = 0, bodyText = null).isHostUnreachableFailure())
        assertFalse(HomeNetworkResponse(code = 503, bodyText = null, transportDiagnostics = "kind=DNS").isHostUnreachableFailure())
    }

    @Test
    fun aSuccessOrAnyHttpStatusInBetweenResetsTheCount() {
        val gate = gate()
        gate.record(failure("DNS"), probe = false)
        gate.record(failure("DNS"), probe = false)
        gate.record(HomeNetworkResponse(code = 500, bodyText = null), probe = false)
        gate.record(failure("DNS"), probe = false)
        gate.record(failure("DNS"), probe = false)
        assertFalse(gate.isOpen)
    }

    @Test
    fun aProbeThatGetsAnyHttpResponseClosesTheGate() {
        val gate = gate()
        repeat(3) { gate.record(failure("CONNECT"), probe = false) }
        now += 1_000
        assertEquals(RaftHostReachabilityGate.Admission.Proceed(probe = true), gate.admit())
        gate.record(HomeNetworkResponse(code = 401, bodyText = null), probe = true)
        assertFalse(gate.isOpen)
        assertEquals(RaftHostReachabilityGate.Admission.Proceed(probe = false), gate.admit())
    }

    @Test
    fun anAbandonedOrInconclusiveProbeLetsTheNextRequestProbe() {
        val gate = gate()
        repeat(3) { gate.record(failure("DNS"), probe = false) }
        now += 1_000
        assertEquals(RaftHostReachabilityGate.Admission.Proceed(probe = true), gate.admit())
        gate.abandon(probe = true)
        assertEquals(RaftHostReachabilityGate.Admission.Proceed(probe = true), gate.admit())
        gate.record(failure("TIMEOUT"), probe = true)
        assertEquals(RaftHostReachabilityGate.Admission.Proceed(probe = true), gate.admit())
    }

    @Test
    fun networkAvailableClosesEveryGateAndGatesAreSeparatePerEnvironment() {
        val gates = RaftHostReachabilityGates { gate() }
        val production = gates.forBaseUrl("https://api.raft.build/")
        repeat(3) { production.record(failure("DNS"), probe = false) }
        assertTrue(gates.forBaseUrl("https://api.raft.build").isOpen)
        assertFalse(gates.forBaseUrl("https://staging.api.raft.build").isOpen)
        gates.onNetworkAvailable()
        assertFalse(production.isOpen)
    }

    private fun failure(kind: String) =
        HomeNetworkResponse(
            code = 0,
            bodyText = null,
            errorBodyText = DNS_ERROR,
            transportDiagnostics = "engine=networkkmm status=none kind=$kind rawCode=6"
        )

    private companion object {
        const val DNS_ERROR = "[dns]_CURLcode:6_Could_not_resolve_host:_api.raft.build"
    }
}
