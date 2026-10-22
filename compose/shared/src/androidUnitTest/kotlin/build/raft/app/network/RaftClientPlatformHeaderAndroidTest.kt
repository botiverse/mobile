package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/** botiverse/slock `signupClientPlatform` reads `X-Raft-Client-Platform: android` as the Android app. */
class RaftClientPlatformHeaderAndroidTest {
    @Test
    fun apiRequestsNameTheAndroidApp() = runTest {
        val byPath = RaftClientPlatformHeaderProbe.clientPlatformByPath()

        assertEquals(RaftClientPlatformHeaderProbe.expectedPaths, byPath.keys)
        assertEquals(RaftClientPlatformHeaderProbe.expectedPaths.associateWith { "android" }, byPath)
    }
}
