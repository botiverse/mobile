package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertEquals

/** The User-Agent base NetworkKMM used to build, now Raft-owned (Ktor 3 switch). */
class RaftAppIdentityTest {
    @Test
    fun userAgentCarriesNameVersionBuildPlatformAndBuildType() {
        assertEquals(
            "Raft/1.10.0+1100006 (Android 36; alpha)",
            raftBaseUserAgent(RaftAppIdentity("Raft", "1.10.0", "1100006", "alpha"), "Android 36")
        )
        assertEquals(
            "Raft/1.10.0 (iOS 18.2)",
            raftBaseUserAgent(RaftAppIdentity("Raft", "1.10.0", " ", ""), "iOS 18.2")
        )
        assertEquals("unknown/unknown (Android 36)", raftBaseUserAgent(null, "Android 36"))
    }

    @Test
    fun deviceTokenIsAppendedOnce() {
        val base = raftBaseUserAgent(RaftAppIdentity("Raft", "1.0", "1", "release"), "Android 36")
        assertEquals("$base Mobile", raftNetworkUserAgent(base, RaftUserAgentDevice.AndroidPhone))
        assertEquals(base, raftNetworkUserAgent(base, RaftUserAgentDevice.AndroidTablet))
    }
}
