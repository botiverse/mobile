package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.network.RaftAppIdentity
import build.raft.app.network.RaftAppIdentityRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(RaftCoreInternalApi::class)
class RaftClientTraceEventsTest {
    @Test
    fun defaultAttrsIncludeTrimmedAppBuildFromAppIdentityRegistry() {
        try {
            RaftAppIdentityRegistry.install(
                RaftAppIdentity(
                    name = "Raft",
                    version = "1.30.0",
                    buildNumber = " 130042 ",
                    buildType = "alpha",
                )
            )
            assertEquals("130042", raftClientTraceDefaultAttrs()["app_build"])

            RaftAppIdentityRegistry.install(
                RaftAppIdentity(
                    name = "Raft",
                    version = "1.30.0",
                    buildNumber = "   ",
                    buildType = "alpha",
                )
            )
            assertFalse("app_build" in raftClientTraceDefaultAttrs())

            RaftAppIdentityRegistry.install(null)
            // When no identity is installed, app_build is present only if platform provides one.
            val withoutInstalled = raftClientTraceDefaultAttrs()["app_build"]
            if (withoutInstalled != null) {
                assertEquals(withoutInstalled.trim(), withoutInstalled)
            }
        } finally {
            RaftAppIdentityRegistry.resetForTest()
        }
    }
}
