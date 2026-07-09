package build.raft.app

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlockShellStoreLaunchTest {
    @Test
    fun launchScopeReadsOnlyShellAndServerIdentity() {
        val scope =
            JSONObject()
                .put(SLOCK_SHELL_INSTANCE_ID, " shell-a ")
                .put(SLOCK_SHELL_SERVER_ID, " server-2 ")
                .put("title", "must-not-enter-scope")
                .slockShellStoreScope("fallback")

        assertEquals("shell-a", scope.shellInstanceId)
        assertEquals("server-2", scope.initialServerId)
    }

    @Test
    fun launchScopeFallsBackToPagerIdentityWithoutInventingServerState() {
        val scope = JSONObject().slockShellStoreScope("pager-42")

        assertEquals("pager-42", scope.shellInstanceId)
        assertNull(scope.initialServerId)
    }
}
