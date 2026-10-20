package build.raft.app.network

import com.tencent.kmm.network.export.NetworkCurlDohFallbackProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task #153: the default-on mobile_doh_fallback_v0 gate in front of NetworkKMM's DoH providers. */
class RaftDohFallbackPolicyTest {
    private val applied = mutableListOf<List<NetworkCurlDohFallbackProvider>>()
    private val disabledServers = mutableSetOf<String>()
    private var zoneId: String? = "Asia/Shanghai"
    private val policy = RaftDohFallbackPolicy(applyProviders = { applied += it }, systemZoneId = { zoneId })
    private val off = emptyList<NetworkCurlDohFallbackProvider>()
    private val aliFirst = listOf(NetworkCurlDohFallbackProvider.ALIDNS, NetworkCurlDohFallbackProvider.CLOUDFLARE)
    private val cloudflareFirst = listOf(NetworkCurlDohFallbackProvider.CLOUDFLARE, NetworkCurlDohFallbackProvider.ALIDNS)

    @Test
    fun onBeforeAnyServerIsSelected() {
        val line = policy.bind { it !in disabledServers }
        assertEquals(listOf(aliFirst), applied, "binding publishes the on state once")
        assertTrue(line!!.contains("enabled=true zone=Asia/Shanghai providers=alidns,cloudflare"))
    }

    @Test
    fun anExplicitOffOnEveryActiveServerTurnsItOff() {
        policy.bind { it !in disabledServers }
        disabledServers += "server-a"
        policy.updateActiveSelection("shell-1", "server-a")
        assertEquals(off, applied.last())

        // Any active server that still has it on turns it back on.
        policy.updateActiveSelection("shell-2", "server-b")
        assertEquals(aliFirst, applied.last())

        policy.updateActiveSelection("shell-2", null)
        assertEquals(off, applied.last())
    }

    @Test
    fun flagChangesApplyOnReevaluationAndRepeatsAreNoOps() {
        policy.bind { it !in disabledServers }
        policy.updateActiveSelection("shell-1", "server-a")
        val before = applied.size

        assertNull(policy.reevaluate("evaluation"))
        assertNull(policy.updateActiveSelection("shell-1", "server-a"))
        assertEquals(before, applied.size)

        disabledServers += "server-a"
        policy.reevaluate("evaluation")
        assertEquals(off, applied.last())

        disabledServers -= "server-a"
        policy.reevaluate("override")
        assertEquals(aliFirst, applied.last())
    }

    @Test
    fun aFailingFlagReadIsNoDecisionAndStaysOn() {
        policy.bind { error("store unavailable") }
        policy.updateActiveSelection("shell-1", "server-a")
        assertEquals(listOf(aliFirst), applied)
    }

    @Test
    fun providerOrderFollowsTheSystemTimeZone() {
        assertEquals(aliFirst, RaftDohFallbackPolicy.providersFor("Asia/Shanghai"))
        assertEquals(aliFirst, RaftDohFallbackPolicy.providersFor("Asia/Urumqi"))
        assertEquals(cloudflareFirst, RaftDohFallbackPolicy.providersFor("America/Los_Angeles"))
        assertEquals(cloudflareFirst, RaftDohFallbackPolicy.providersFor("Europe/Berlin"))
        assertEquals(cloudflareFirst, RaftDohFallbackPolicy.providersFor("Asia/Hong_Kong"))
        assertEquals(cloudflareFirst, RaftDohFallbackPolicy.providersFor("Asia/Tokyo"))
        // Unknown: keep the previous default.
        assertEquals(aliFirst, RaftDohFallbackPolicy.providersFor(null))
        assertEquals(aliFirst, RaftDohFallbackPolicy.providersFor(""))
    }

    @Test
    fun anOverseasDeviceTriesCloudflareFirstAndAZoneChangeRepublishes() {
        zoneId = "America/New_York"
        val line = policy.bind { true }
        assertEquals(listOf(cloudflareFirst), applied)
        assertTrue(line!!.contains("providers=cloudflare,alidns"))

        zoneId = "Asia/Shanghai"
        policy.reevaluate("evaluation")
        assertEquals(aliFirst, applied.last())
        assertNull(policy.reevaluate("evaluation"), "same order is not republished")
    }

    @Test
    fun aFailingZoneReadKeepsAliDnsFirst() {
        val failing = RaftDohFallbackPolicy(applyProviders = { applied += it }, systemZoneId = { error("no zone") })
        failing.bind { true }
        assertEquals(listOf(aliFirst), applied)
    }
}
