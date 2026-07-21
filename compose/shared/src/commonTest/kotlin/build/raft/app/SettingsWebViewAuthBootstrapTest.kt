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
        val storage =
            mutableMapOf(
                "slock_access_token" to "stale-access",
                "slock_refresh_token" to "stale-web-refresh",
                "slock_last_server_slug" to "stale-server",
            )

        settingsWebViewAuthStorageMutations(session).forEach { mutation ->
            mutation.value?.let { storage[mutation.key] = it } ?: storage.remove(mutation.key)
        }

        assertEquals(session.accessToken, storage["slock_access_token"])
        assertEquals(session.selectedServerSlug, storage["slock_last_server_slug"])
        assertFalse(storage.containsKey("slock_refresh_token"))
        assertEquals("native-refresh-secret", session.refreshToken)
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
}
