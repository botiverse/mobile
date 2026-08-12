package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AndroidCurlNetworkEnvironmentIdentityTest {
    @Test
    fun identityChangesWithNetworkVpnOrProxyButIsStableForEquivalentInputs() {
        fun identity(
            network: String = "123",
            vpn: Boolean = false,
            proxyHost: String? = null,
            proxyPort: Int? = null
        ) = androidCurlNetworkEnvironmentIdentity(
            network = network,
            wifi = true,
            cellular = false,
            ethernet = false,
            vpn = vpn,
            interfaceName = "wlan0",
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyPacUrl = null
        )

        val direct = identity()
        assertEquals(direct, identity())
        assertNotEquals(direct, identity(network = "456"))
        assertNotEquals(direct, identity(vpn = true))
        assertNotEquals(direct, identity(proxyHost = "127.0.0.1", proxyPort = 8888))
        val proxied = identity(vpn = true, proxyHost = "127.0.0.1", proxyPort = 8888)
        assertEquals(40, proxied.length)
        assertTrue(proxied.startsWith("android:"))
        assertFalse(proxied.contains("123"))
        assertFalse(proxied.contains("wlan0"))
        assertFalse(proxied.contains("127.0.0.1"))
    }

    @Test
    fun pacIdentityIsStableAndDoesNotRetainThePacUrl() {
        fun identity(pacUrl: String?) = androidCurlNetworkEnvironmentIdentity(
            network = "123",
            wifi = true,
            cellular = false,
            ethernet = false,
            vpn = true,
            interfaceName = "tun0",
            proxyHost = "127.0.0.1",
            proxyPort = 8888,
            proxyPacUrl = pacUrl
        )

        val first = identity("https://proxy.example/private/config.pac?account=123")
        assertEquals(first, identity("https://proxy.example/private/config.pac?account=123"))
        assertNotEquals(first, identity("https://proxy.example/private/other.pac?account=123"))
        assertFalse(first.contains("proxy.example"))
        assertFalse(first.contains("account=123"))
    }
}
