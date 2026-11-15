package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The device token the server's User-Agent parser needs for `$device_type` (botiverse/slock
 * `productEventContext`, Bowser 2.14): Android `Mobile` = phone, no `Mobile` = tablet;
 * `iPhone` = phone, `iPad` = tablet.
 */
class RaftNetworkUserAgentTest {
    private val androidBase = "Raft/1.15.1+11501001 (Android 34; release) NetworkKMM/0.1.0-raft.47"
    private val iosBase = "Raft/1.15.1+1 (iOS; release) NetworkKMM/0.1.0-raft.47"

    @Test
    fun androidPhoneAppendsMobileAndTabletKeepsTheBase() {
        assertEquals("$androidBase Mobile", raftNetworkUserAgent(androidBase, RaftUserAgentDevice.AndroidPhone))
        assertEquals(androidBase, raftNetworkUserAgent(androidBase, RaftUserAgentDevice.AndroidTablet))
        assertEquals(androidBase, raftNetworkUserAgent(androidBase, null), "device not installed yet: unchanged")
    }

    @Test
    fun iosNamesTheIPhoneOrTheIPad() {
        assertEquals("$iosBase iPhone", raftNetworkUserAgent(iosBase, RaftUserAgentDevice.IPhone))
        assertEquals("$iosBase iPad", raftNetworkUserAgent(iosBase, RaftUserAgentDevice.IPad))
    }

    @Test
    fun theTokenIsNotRepeated() {
        val phone = raftNetworkUserAgent(androidBase, RaftUserAgentDevice.AndroidPhone)
        assertEquals(phone, raftNetworkUserAgent(phone, RaftUserAgentDevice.AndroidPhone))
    }

    @Test
    fun androidSmallestWidthLineIs600dp() {
        assertEquals(RaftUserAgentDevice.AndroidPhone, raftAndroidUserAgentDevice(411))
        assertEquals(RaftUserAgentDevice.AndroidPhone, raftAndroidUserAgentDevice(599))
        assertEquals(RaftUserAgentDevice.AndroidTablet, raftAndroidUserAgentDevice(600))
        assertEquals(RaftUserAgentDevice.AndroidTablet, raftAndroidUserAgentDevice(800))
    }

    @Test
    fun apiRequestsCarryTheUserAgentUnlessTheCallerSetOne() {
        val headers = mapOf("X-Server-Id" to "srv")
        assertEquals(
            headers + ("User-Agent" to "$androidBase Mobile"),
            withRaftNetworkUserAgent(headers) { "$androidBase Mobile" },
        )
        val own = mapOf("user-agent" to "custom")
        assertSame(own, withRaftNetworkUserAgent(own) { "$androidBase Mobile" })
        assertSame(headers, withRaftNetworkUserAgent(headers) { error("no identity") })
    }

    @Test
    fun aCallersOwnClientPlatformWins() {
        val own = mapOf("x-raft-client-platform" to "custom")
        assertSame(own, withRaftClientPlatform(own) { "android" })
    }

    @Test
    fun apiRequestsCarryTheVersionNameUnlessTheCallerSetOne() {
        val headers = mapOf("X-Server-Id" to "srv")
        assertEquals(headers + ("X-Raft-Client-Version" to "1.26.0"), withRaftClientVersion(headers) { " 1.26.0 " })
        val own = mapOf("x-raft-client-version" to "9.9.9")
        assertSame(own, withRaftClientVersion(own) { "1.26.0" })
        assertSame(headers, withRaftClientVersion(headers) { null }, "no identity installed: no header")
        assertSame(headers, withRaftClientVersion(headers) { "" })
        assertSame(headers, withRaftClientVersion(headers) { error("no identity") })
    }
}
