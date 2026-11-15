package build.raft.app.network

import build.raft.app.network.RaftClientPlatformHeaderProbe.ClientHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * botiverse/slock `signupClientPlatform` reads `X-Raft-Client-Platform: ios` as the iOS app; the
 * signed-in devices list (`GET /api/me/sessions`) shows it with `X-Raft-Client-Version`, the version name.
 * Sign-in and token refresh carry both: those requests are what stamp the session.
 */
class RaftClientPlatformHeaderIosTest {
    @Test
    fun apiRequestsNameTheIosAppAndItsVersion() = runTest {
        val byPath = RaftClientPlatformHeaderProbe.clientHeadersByPath()

        assertEquals(RaftClientPlatformHeaderProbe.expectedPaths, byPath.keys)
        assertEquals(
            RaftClientPlatformHeaderProbe.expectedPaths.associateWith {
                ClientHeaders(platform = "ios", version = RaftClientPlatformHeaderProbe.VERSION_NAME)
            },
            byPath,
        )
    }
}
