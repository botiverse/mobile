package build.raft.app.data

import build.raft.app.network.MultipartFile
import build.raft.app.network.SlockHttpExecutor
import build.raft.app.network.SlockHttpExecutorResponse
import build.raft.app.network.SlockRequestPriority
import build.raft.app.network.SlockTransferProgress
import build.raft.app.runPortableCoroutineTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Task #150 (Hands 6604f4c4): an unreachable API host must not be hammered by every page switch.
 */
class SlockHostReachabilityGateTest {
    private var now = 0L
    private fun gate() = SlockHostReachabilityGate(nowMillis = { now })

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
    fun opensAfterThreeConsecutiveUnreachableFailuresAndAnswersWithTheLastFailure() {
        val gate = gate()
        repeat(2) {
            assertEquals(SlockHostReachabilityGate.Admission.Proceed(probe = false), gate.admit())
            gate.record(failure("DNS"), probe = false)
        }
        assertFalse(gate.isOpen)
        gate.admit()
        gate.record(failure("DNS"), probe = false)
        assertTrue(gate.isOpen)

        val blocked = assertIs<SlockHostReachabilityGate.Admission.Blocked>(gate.admit())
        assertEquals(0, blocked.response.code)
        // Same error text, so repositories keep mapping it to network_dns_failed / http_0 as before.
        assertEquals(DNS_ERROR, blocked.response.errorBodyText)
        assertEquals("DNS", blocked.response.transportKind())
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
    fun afterTheCooldownOneProbeGoesOutAndItsFailureDoublesTheCooldownUpToTheCap() {
        val gate = gate()
        repeat(3) { gate.record(failure("DNS"), probe = false) }
        assertIs<SlockHostReachabilityGate.Admission.Blocked>(gate.admit())

        val cooldowns = mutableListOf<Long>()
        repeat(6) {
            val opened = now
            // Blocked until the cooldown ends.
            while (gate.admit() is SlockHostReachabilityGate.Admission.Blocked) now += 100
            cooldowns += now - opened
            // Exactly one probe; everything else stays blocked while it is out.
            assertIs<SlockHostReachabilityGate.Admission.Blocked>(gate.admit())
            gate.record(failure("DNS"), probe = true)
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L), cooldowns)
    }

    @Test
    fun aProbeThatGetsAnyHttpResponseClosesTheGate() {
        val gate = gate()
        repeat(3) { gate.record(failure("CONNECT"), probe = false) }
        now += 1_000
        assertEquals(SlockHostReachabilityGate.Admission.Proceed(probe = true), gate.admit())
        gate.record(HomeNetworkResponse(code = 401, bodyText = null), probe = true)
        assertFalse(gate.isOpen)
        assertEquals(SlockHostReachabilityGate.Admission.Proceed(probe = false), gate.admit())
    }

    @Test
    fun anAbandonedOrInconclusiveProbeLetsTheNextRequestProbe() {
        val gate = gate()
        repeat(3) { gate.record(failure("DNS"), probe = false) }
        now += 1_000
        assertEquals(SlockHostReachabilityGate.Admission.Proceed(probe = true), gate.admit())
        gate.abandon(probe = true)
        assertEquals(SlockHostReachabilityGate.Admission.Proceed(probe = true), gate.admit())
        gate.record(failure("TIMEOUT"), probe = true)
        assertEquals(SlockHostReachabilityGate.Admission.Proceed(probe = true), gate.admit())
    }

    @Test
    fun stragglersThatFailAfterTheGateOpenedDoNotExtendIt() {
        val gate = gate()
        repeat(3) { gate.record(failure("DNS"), probe = false) }
        repeat(20) { gate.record(failure("DNS"), probe = false) }
        now += 1_000
        assertEquals(SlockHostReachabilityGate.Admission.Proceed(probe = true), gate.admit())
    }

    @Test
    fun networkAvailableClosesEveryGateAndGatesAreSeparatePerEnvironment() {
        val gates = SlockHostReachabilityGates { gate() }
        val production = gates.forBaseUrl("https://api.raft.build/")
        repeat(3) { production.record(failure("DNS"), probe = false) }
        assertTrue(gates.forBaseUrl("https://api.raft.build").isOpen)
        assertFalse(gates.forBaseUrl("https://staging.api.raft.build").isOpen)
        gates.onNetworkAvailable()
        assertFalse(production.isOpen)
    }

    @Test
    fun theClientStopsReachingTheNetworkWhileTheHostIsUnreachable() = runPortableCoroutineTest<Unit> {
        val executor = DnsFailingExecutor()
        val gates = SlockHostReachabilityGates { gate() }
        val client = SharedSlockNetworkClient(
            sessionStore = authenticatedSessionStore(),
            baseUrlProvider = { SharedApiEnvironmentStore.Production.apiBaseUrl },
            executor = executor,
            reachabilityGates = gates,
        )
        // A page switch fans out 25 requests while DNS is down.
        val responses = (1..25).map { client.request(DataRequestPriority.Visible, "channels/inbox") }
        assertEquals(3, executor.calls, "only the requests that discovered the outage reach the network")
        responses.forEach {
            assertEquals(0, it.code)
            assertEquals(DNS_ERROR, it.errorBodyText)
        }

        // The cooldown ends: one probe; DNS is back, so the gate closes and traffic flows again.
        now += 1_000
        executor.reachable = true
        assertEquals(200, client.request(DataRequestPriority.Visible, "channels/inbox").code)
        repeat(5) { assertEquals(200, client.request(DataRequestPriority.Visible, "channels/dm").code) }
        assertEquals(9, executor.calls)
    }

    private class DnsFailingExecutor : SlockHttpExecutor {
        var calls = 0
        var reachable = false

        override suspend fun execute(
            priority: SlockRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
            jsonBody: Boolean
        ): SlockHttpExecutorResponse {
            calls += 1
            return if (reachable) {
                SlockHttpExecutorResponse(code = 200, bodyText = "{}", errorBodyText = null)
            } else {
                SlockHttpExecutorResponse(
                    code = 0,
                    bodyText = null,
                    errorBodyText = DNS_ERROR,
                    transportDiagnostics = "engine=networkkmm status=none kind=DNS rawCode=6"
                )
            }
        }

        override suspend fun executeMultipart(
            priority: SlockRequestPriority,
            url: String,
            fields: Map<String, String>,
            files: List<MultipartFile>,
            headers: Map<String, String>,
            onUploadProgress: ((SlockTransferProgress) -> Unit)?
        ): SlockHttpExecutorResponse = error("unused")
    }

    private fun authenticatedSessionStore(): InMemorySlockAuthSessionStore =
        InMemorySlockAuthSessionStore(
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "user@raft.build"),
                accessToken = "access-token",
                refreshToken = "refresh-token",
                selectedServerId = "server-1",
                servers = listOf(StoredAuthServer(id = "server-1", name = "Raft")),
            )
        )

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
