package build.raft.app.core.data.realtime

import build.raft.app.network.SlockDohEndpoint
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task #153: the realtime socket's DoH fallback never replaces a working system resolver. */
class SlockRealtimeDnsTest {
    private val alidns = SlockDohEndpoint("alidns", "https://dns.alidns.com/dns-query", listOf("223.5.5.5"))
    private val cloudflare = SlockDohEndpoint("cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1"))
    private val resolved = listOf(InetAddress.getByAddress("api.raft.build", byteArrayOf(10, 0, 0, 1)))
    private val dohCalls = mutableListOf<String>()
    private val logs = mutableListOf<String>()

    private fun dns(
        system: Dns,
        endpoints: List<SlockDohEndpoint>,
        doh: (SlockDohEndpoint, String) -> List<InetAddress> = { endpoint, _ -> dohCalls += endpoint.name; resolved },
    ) = SlockRealtimeDns(system = system, activeEndpoints = { endpoints }, dohLookup = doh, log = { logs += it })

    private val failingSystem = Dns { throw UnknownHostException("system resolver down") }

    @Test
    fun aWorkingSystemResolverIsNeverReplaced() {
        val system = Dns { resolved }
        assertSame(resolved, dns(system, listOf(alidns)).lookup("api.raft.build"))
        assertTrue(dohCalls.isEmpty())
    }

    @Test
    fun anUnresolvedHostFallsBackThroughTheEndpointsInOrder() {
        val result =
            dns(failingSystem, listOf(alidns, cloudflare)) { endpoint, host ->
                dohCalls += endpoint.name
                if (endpoint == alidns) throw UnknownHostException(host) else resolved
            }.lookup("api.raft.build")
        assertEquals(resolved, result)
        assertEquals(listOf("alidns", "cloudflare"), dohCalls)
        assertTrue(logs.single().contains("doh_fallback_success provider=cloudflare"))
    }

    @Test(expected = UnknownHostException::class)
    fun offMeansTheSystemFailureStands() {
        dns(failingSystem, emptyList()).lookup("api.raft.build")
    }

    @Test
    fun everyEndpointFailingRethrowsTheSystemFailure() {
        val failure =
            runCatching {
                dns(failingSystem, listOf(alidns)) { _, host -> throw UnknownHostException(host) }.lookup("api.raft.build")
            }.exceptionOrNull()
        assertEquals("system resolver down", failure?.message)
        assertTrue(logs.single().contains("doh_fallback_failure"))
    }
}
