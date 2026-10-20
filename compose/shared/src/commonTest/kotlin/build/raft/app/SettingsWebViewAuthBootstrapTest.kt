package build.raft.app

import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SettingsWebViewAuthBootstrapTest {
    @Test
    fun authenticatedBootstrapRemovesPersistedRefreshOwnerWithoutExposingNativeToken() {
        val session = session()
        val binding = hostBinding(session)
        val storage =
            mutableMapOf(
                "slock_access_token" to "stale-access",
                "slock_refresh_token" to "stale-web-refresh",
                "slock_last_server_slug" to "stale-server",
            )

        settingsWebViewAuthStorageMutations(session, binding).forEach { mutation ->
            mutation.value?.let { storage[mutation.key] = it } ?: storage.remove(mutation.key)
        }

        assertEquals(session.accessToken, storage["slock_access_token"])
        assertEquals(session.selectedServerSlug, storage["slock_last_server_slug"])
        assertFalse(storage.containsKey("slock_refresh_token"))
        assertEquals(
            "{\"accountId\":\"user-1\",\"serverId\":\"server-1\",\"sessionGeneration\":42,\"webViewGeneration\":7}",
            storage[RAFT_HOST_AUTH_BINDING_STORAGE_KEY],
        )
        assertEquals("native-refresh-secret", session.refreshToken)
    }

    @Test
    fun missingSessionOrBindingRemovesStaleHostBinding() {
        val storage =
            mutableMapOf(
                RAFT_HOST_AUTH_BINDING_STORAGE_KEY to "stale",
                "slock_access_token" to "stale-access",
            )

        settingsWebViewAuthStorageMutations(session = null, hostBinding = null).forEach { mutation ->
            mutation.value?.let { storage[mutation.key] = it } ?: storage.remove(mutation.key)
        }

        assertFalse(storage.containsKey(RAFT_HOST_AUTH_BINDING_STORAGE_KEY))
        assertFalse(storage.containsKey("slock_access_token"))
    }

    private fun session() =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.test"),
            accessToken = "native-access",
            refreshToken = "native-refresh-secret",
            selectedServerId = "server-1",
            selectedServerSlug = "workspace",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Workspace", slug = "workspace")),
        )

    private fun hostBinding(session: StoredAuthSession): SettingsWebViewHostAuthBinding {
        val fence =
            SettingsWebViewAccessTokenFence(
                initialSession = session,
                sessionGeneration = 42L,
                webViewGeneration = 7L,
            )
        return SettingsWebViewHostAuthBinding(
            accountId = "user-1",
            serverId = "server-1",
            sessionGeneration = 42L,
            webViewGeneration = 7L,
            fence = fence,
        )
    }
}
