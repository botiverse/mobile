package build.raft.app.network

import com.tencent.kmm.network.export.NetworkAppIdentity
import com.tencent.kmm.network.export.NetworkUserAgent
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class NetworkKmmAppIdentityTest {
    @Test
    fun appIdentityIncludesRaftVersionBuildAndChannel() {
        val previous = NetworkUserAgent.appIdentity
        try {
            installNetworkKmmAppIdentity(
                NetworkAppIdentity(
                    name = "Raft",
                    version = "1.10.0",
                    buildNumber = "1100006",
                    buildType = "alpha"
                )
            )

            val value = NetworkUserAgent.headerValue()
            assertTrue(value.startsWith("Raft/1.10.0+1100006 ("), value)
            assertContains(value, "; alpha)")
        } finally {
            NetworkUserAgent.appIdentity = previous
        }
    }
}
