package build.raft.app.network

import com.tencent.kmm.network.export.NetworkCurlDohFallbackProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task #153: the default-on mobile_doh_fallback_v0 gate in front of NetworkKMM's DoH providers. */
class SlockDohFallbackPolicyTest {
    private val applied = mutableListOf<List<NetworkCurlDohFallbackProvider>>()
    private val disabledServers = mutableSetOf<String>()
    private val policy = SlockDohFallbackPolicy(applyProviders = { applied += it })
    private val off = emptyList<NetworkCurlDohFallbackProvider>()

    @Test
    fun onBeforeAnyServerIsSelected() {
        val line = policy.bind { it !in disabledServers }
        assertEquals(listOf(SlockDohFallbackPolicy.PROVIDERS), applied, "binding publishes the on state once")
        assertTrue(line!!.contains("enabled=true providers=alidns,cloudflare"))
    }

    @Test
    fun anExplicitOffOnEveryActiveServerTurnsItOff() {
        policy.bind { it !in disabledServers }
        disabledServers += "server-a"
        policy.updateActiveSelection("shell-1", "server-a")
        assertEquals(off, applied.last())

        // Any active server that still has it on turns it back on.
        policy.updateActiveSelection("shell-2", "server-b")
        assertEquals(SlockDohFallbackPolicy.PROVIDERS, applied.last())

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
        assertEquals(SlockDohFallbackPolicy.PROVIDERS, applied.last())
    }

    @Test
    fun aFailingFlagReadIsNoDecisionAndStaysOn() {
        policy.bind { error("store unavailable") }
        policy.updateActiveSelection("shell-1", "server-a")
        assertEquals(listOf(SlockDohFallbackPolicy.PROVIDERS), applied)
    }
}
