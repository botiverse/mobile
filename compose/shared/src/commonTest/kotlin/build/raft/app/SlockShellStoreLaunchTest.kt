package build.raft.app

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun launchScopeReadsLegacyServerIdentityWhenDedicatedOwnerKeyIsAbsent() {
        val scope =
            JSONObject()
                .put(SLOCK_SHELL_INSTANCE_ID, "shell-a")
                .put("serverId", "server-legacy")
                .slockShellStoreScope("fallback")

        assertEquals("server-legacy", scope.initialServerId)
    }

    @Test
    fun dedicatedOwnerServerIdentityTakesPrecedenceOverLegacyRouteServer() {
        val scope =
            JSONObject()
                .put(SLOCK_SHELL_INSTANCE_ID, "shell-a")
                .put(SLOCK_SHELL_SERVER_ID, "server-owner")
                .put("serverId", "server-target")
                .slockShellStoreScope("fallback")

        assertEquals("server-owner", scope.initialServerId)
    }

    @Test
    fun launchScopeFallsBackToPagerIdentityWithoutInventingServerState() {
        val scope = JSONObject().slockShellStoreScope("pager-42")

        assertEquals("pager-42", scope.shellInstanceId)
        assertNull(scope.initialServerId)
    }

    @Test
    fun inheritedLaunchScopeFailsClosedWhenSourceHasNoShellIdentity() {
        assertNull(JSONObject().put(SLOCK_SHELL_SERVER_ID, "server-2").slockShellStoreScopeOrNull())
    }

    @Test
    fun inheritedLaunchScopeAddsOnlyOwnerIdentityToFeaturePageData() {
        val pageData =
            JSONObject()
                .put("memberId", "agent-1")
                .put("serverId", "server-target")
                .withSlockShellStoreScope(
                    build.raft.app.data.SlockShellStoreScope.create("shell-a", "server-owner")
                )

        assertEquals("agent-1", pageData.optString("memberId"))
        assertEquals("server-target", pageData.optString("serverId"))
        assertEquals("shell-a", pageData.optString(SLOCK_SHELL_INSTANCE_ID))
        assertEquals("server-owner", pageData.optString(SLOCK_SHELL_SERVER_ID))
        assertEquals("", pageData.optString("displayName"))
        assertEquals("", pageData.optString("avatarUrl"))
    }

    @Test
    fun threadLaunchDescriptorCarriesRouteIntentButNoAvatarCacheProductState() {
        val pageData =
            threadLaunchPageData(
                WireThreadLaunchPayload(
                    instanceId = "thread-instance",
                    threadId = "channel-a",
                    streamChannelId = "channel-a"
                )
            )

        assertEquals(THREAD_PAGE, pageData.optString("pageName"))
        assertEquals("thread-instance", pageData.optString("instanceId"))
        val payload = pageData.optString("payload")
        assertEquals("channel-a", parseThreadScreenPayload(payload, "fallback").streamChannelId)
        listOf(
            "avatarCache",
            "nativePartitionKey",
            "nativeSubjectKey",
            "nativeBytesKey",
            "commitGeneration",
            "imageBlob",
            "nativePath"
        ).forEach { key ->
            assertEquals("", pageData.optString(key))
            assertFalse(payload.contains(key))
        }
    }
}
