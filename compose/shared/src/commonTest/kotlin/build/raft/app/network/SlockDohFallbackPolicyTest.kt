package build.raft.app.network

import com.tencent.kmm.network.export.NetworkCurlDohFallbackProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task #153: the mobile_doh_fallback_v0 gate in front of NetworkKMM's DoH providers. */
class SlockDohFallbackPolicyTest {
    private val applied = mutableListOf<List<NetworkCurlDohFallbackProvider>>()
    private val enabledServers = mutableSetOf<String>()
    private val policy = SlockDohFallbackPolicy(applyProviders = { applied += it })

    @Test
    fun offUntilAnActiveServerHasTheFlag() {
        policy.bind { it in enabledServers }
        assertEquals(listOf(emptyList<NetworkCurlDohFallbackProvider>()), applied, "binding publishes the off state once")

        enabledServers += "server-a"
        // Not selected yet: an evaluation alone does not turn it on.
        assertNull(policy.reevaluate("evaluation"))

        val line = policy.updateActiveSelection("shell-1", "server-a")
        assertEquals(SlockDohFallbackPolicy.PROVIDERS, applied.last())
        assertTrue(line!!.contains("enabled=true providers=alidns,cloudflare"))
    }

    @Test
    fun anyActiveServerEnablesItAndTheLastOneLeavingDisablesIt() {
        enabledServers += "server-a"
        policy.bind { it in enabledServers }
        policy.updateActiveSelection("shell-1", "server-a")
        policy.updateActiveSelection("shell-2", "server-b")
        assertEquals(SlockDohFallbackPolicy.PROVIDERS, applied.last())

        policy.updateActiveSelection("shell-1", null)
        assertEquals(emptyList<NetworkCurlDohFallbackProvider>(), applied.last())
    }

    @Test
    fun flagChangesApplyOnReevaluationAndRepeatsAreNoOps() {
        policy.bind { it in enabledServers }
        policy.updateActiveSelection("shell-1", "server-a")
        val before = applied.size

        assertNull(policy.reevaluate("evaluation"))
        assertNull(policy.updateActiveSelection("shell-1", "server-a"))
        assertEquals(before, applied.size)

        enabledServers += "server-a"
        policy.reevaluate("override")
        assertEquals(SlockDohFallbackPolicy.PROVIDERS, applied.last())

        enabledServers -= "server-a"
        policy.reevaluate("evaluation")
        assertEquals(emptyList<NetworkCurlDohFallbackProvider>(), applied.last())
    }

    @Test
    fun aFailingFlagReadCountsAsOff() {
        policy.bind { error("store unavailable") }
        policy.updateActiveSelection("shell-1", "server-a")
        assertEquals(listOf(emptyList<NetworkCurlDohFallbackProvider>()), applied)
    }
}
