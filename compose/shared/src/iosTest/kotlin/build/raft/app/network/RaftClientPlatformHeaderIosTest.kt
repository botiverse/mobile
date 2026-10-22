package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/** botiverse/slock `signupClientPlatform` reads `X-Raft-Client-Platform: ios` as the iOS app. */
class RaftClientPlatformHeaderIosTest {
    @Test
    fun apiRequestsNameTheIosApp() = runTest {
        val byPath = RaftClientPlatformHeaderProbe.clientPlatformByPath()

        assertEquals(RaftClientPlatformHeaderProbe.expectedPaths, byPath.keys)
        assertEquals(RaftClientPlatformHeaderProbe.expectedPaths.associateWith { "ios" }, byPath)
    }
}
